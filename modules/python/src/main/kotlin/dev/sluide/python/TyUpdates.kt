package dev.sluide.python

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.localmodels.ToolRelease
import javafx.application.Platform
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = System.getLogger(TyUpdates::class.java.name)

/** The latest release of ty on GitHub (prereleases and drafts are never "latest"). */
private val LATEST = URI.create("https://api.github.com/repos/astral-sh/ty/releases/latest")

/** Where ty's release assets are downloaded from on github.com. */
private const val RELEASE_DOWNLOADS = "/astral-sh/ty/releases/download/"

private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")
private val DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

/** What a check for a newer ty found. */
sealed interface TyUpdateCheck {
    /** No release is newer than [version], the installed one (or the pinned one when none is installed). */
    data class UpToDate(
        val version: String,
    ) : TyUpdateCheck

    /**
     * [version] is newer: [release] installs it on this platform; [newerThanPinned] when it is also newer than the
     * version this Thoreau was tested with; [releaseNotes] is its page on GitHub.
     */
    data class Available(
        val version: String,
        val release: ToolRelease,
        val newerThanPinned: Boolean,
        val releaseNotes: URI?,
    ) : TyUpdateCheck

    /** [version] is newer but has no build for this platform. */
    data class NoBuild(
        val version: String,
        val releaseNotes: URI?,
    ) : TyUpdateCheck

    /** The check failed; [message] says why, for the user. */
    data class Failed(
        val message: String,
    ) : TyUpdateCheck
}

/** An HTTP answer: its status, the headers the check reads (names in lower case) and the body. */
internal data class HttpAnswer(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * Checks GitHub for a newer ty, only when the user asks (Check for Updates; ADR 0144, no background fetch): one
 * `GET` of the latest release from the GitHub API with a 15 s timeout, off the FX thread. A newer version comes with
 * the [ToolRelease] of this platform's asset, its size and SHA-256 from GitHub's `digest`.
 */
@Component
class TyUpdates internal constructor(
    private val pin: TyPin,
    private val platform: String?,
    private val fetch: () -> HttpAnswer,
    private val executor: Executor = Executor { NamedThreads.virtual("ty-update-check", it) },
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) {
    @Autowired
    constructor() : this(TyPin.load(), TyPin.currentPlatform(), { fetchLatest() })

    /** The pinned version: what is compared with when none is installed. */
    val pinnedVersion: String get() = pin.version

    /**
     * Asks GitHub for the latest release and compares it with [installedVersion] (the pin if none is installed);
     * [onResult] gets the answer on the FX thread.
     */
    fun check(
        installedVersion: String?,
        onResult: (TyUpdateCheck) -> Unit,
    ) {
        executor.execute {
            val result =
                try {
                    interpret(fetch(), installedVersion ?: pin.version)
                } catch (_: HttpTimeoutException) {
                    TyUpdateCheck.Failed("GitHub did not answer within ${TIMEOUT.inWholeSeconds} s")
                } catch (e: IOException) {
                    TyUpdateCheck.Failed("Cannot reach GitHub: ${e.message ?: e.javaClass.simpleName}")
                } catch (e: Exception) {
                    // Whatever went wrong, the page gets an answer and stops "checking".
                    log.log(System.Logger.Level.ERROR, "ty update check failed", e)
                    TyUpdateCheck.Failed("The check failed: ${e.message ?: e.javaClass.simpleName}")
                }
            log.log(System.Logger.Level.INFO, "ty update check: $result")
            dispatch(Runnable { onResult(result) })
        }
    }

    /** What [answer] of the GitHub API says about a version newer than [compared]. */
    internal fun interpret(
        answer: HttpAnswer,
        compared: String,
    ): TyUpdateCheck {
        if (answer.status != 200) return TyUpdateCheck.Failed(statusMessage(answer))
        val json =
            try {
                JsonMapper.shared().readTree(answer.body)
            } catch (_: JacksonException) {
                return TyUpdateCheck.Failed("GitHub's answer is not readable")
            }
        val version = json.path("tag_name").asString("").removePrefix("v")
        if (!VERSION.matches(version)) return TyUpdateCheck.Failed("GitHub's latest ty release has no usable version")
        if (json.path("prerelease").asBoolean(false) || compareVersions(version, compared) <= 0) {
            return TyUpdateCheck.UpToDate(compared)
        }
        val notes = webPage(json.path("html_url").asString(""))
        val assetName = pin.asset(platform)?.name ?: return TyUpdateCheck.NoBuild(version, notes)
        val asset =
            json.path("assets").firstOrNull { it.path("name").asString("") == assetName }
                ?: return TyUpdateCheck.NoBuild(version, notes)
        val release =
            release(version, assetName, asset)
                ?: return TyUpdateCheck.Failed("GitHub lists $assetName of ty $version without its size or checksum")
        return TyUpdateCheck.Available(version, release, compareVersions(version, pin.version) > 0, notes)
    }

    private fun release(
        version: String,
        assetName: String,
        asset: JsonNode,
    ): ToolRelease? {
        val bytes = asset.path("size").asLong(0)
        val sha256 = DIGEST.matchEntire(asset.path("digest").asString(""))?.groupValues?.get(1) ?: return null
        val url =
            webPage(asset.path("browser_download_url").asString(""))?.takeIf {
                it.host == "github.com" && it.path.startsWith(RELEASE_DOWNLOADS)
            }
        if (bytes <= 0 || url == null) return null
        return TyPin.release(version, TyAsset(assetName, bytes, sha256.lowercase(Locale.ROOT)), url)
    }

    internal companion object {
        val TIMEOUT: Duration = 15.seconds

        /** The latest release from the GitHub API: JSON, without sign-in. */
        fun fetchLatest(uri: URI = LATEST): HttpAnswer {
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT.toJavaDuration())
                .build()
                .use { client ->
                    val request =
                        HttpRequest
                            .newBuilder(uri)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "Thoreau")
                            .timeout(TIMEOUT.toJavaDuration())
                            .GET()
                            .build()
                    val response =
                        try {
                            client.send(request, HttpResponse.BodyHandlers.ofString())
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("interrupted", e)
                        }
                    val headers =
                        response
                            .headers()
                            .map()
                            .mapKeys { it.key.lowercase(Locale.ROOT) }
                            .mapValues { it.value.firstOrNull().orEmpty() }
                    return HttpAnswer(response.statusCode(), headers, response.body())
                }
        }

        /** Why a non-200 answer failed; GitHub's rate limit for requests without sign-in gets its own text. */
        fun statusMessage(answer: HttpAnswer): String {
            val limited =
                answer.headers["x-ratelimit-remaining"] == "0" ||
                    answer.body.contains("rate limit", ignoreCase = true)
            return when {
                (answer.status == 403 || answer.status == 429) && limited -> {
                    "GitHub's limit of requests without sign-in is used up for now; try again in an hour"
                }

                answer.status == 404 -> {
                    "GitHub has no ty release"
                }

                else -> {
                    "GitHub answered with HTTP status ${answer.status}"
                }
            }
        }

        /** [text] as an `https` URI, or null. */
        fun webPage(text: String): URI? =
            try {
                URI(text).takeIf { it.scheme == "https" && it.host != null }
            } catch (_: URISyntaxException) {
                null
            }

        /**
         * Compares two versions dot segment by dot segment: the leading digits of a segment as a number, then the
         * rest as text, where nothing comes after something (`1.0` > `1.0-rc1`); a missing segment counts as `0`.
         */
        fun compareVersions(
            a: String,
            b: String,
        ): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until maxOf(left.size, right.size)) {
                val c = compareSegments(left.getOrElse(i) { "0" }, right.getOrElse(i) { "0" })
                if (c != 0) return c
            }
            return 0
        }

        private fun compareSegments(
            a: String,
            b: String,
        ): Int {
            val aDigits = a.takeWhile(Char::isDigit)
            val bDigits = b.takeWhile(Char::isDigit)
            val byNumber = compareDigits(aDigits, bDigits)
            if (byNumber != 0) return byNumber
            val aRest = a.substring(aDigits.length)
            val bRest = b.substring(bDigits.length)
            return when {
                aRest == bRest -> 0
                aRest.isEmpty() -> 1
                bRest.isEmpty() -> -1
                else -> aRest.compareTo(bRest)
            }
        }

        /** Compares digit strings as numbers of any length; no digits counts as 0. */
        private fun compareDigits(
            a: String,
            b: String,
        ): Int {
            val x = a.trimStart('0')
            val y = b.trimStart('0')
            return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
        }
    }
}
