package dev.sluide.servertools

import dev.sluide.localmodels.ToolRelease
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** How long an update check waits to connect and for each answer; the messages name it. */
val UPDATE_TIMEOUT: Duration = 15.seconds

/**
 * Where a server tool's newer releases come from (GitHub's releases of ty, the npm registry): asked by [ToolUpdates]
 * only when the user clicks Check for Updates (ADR 0043, ADR 0159). Calls block and run off the FX thread.
 */
interface UpdateSource {
    /** The source in messages: "Cannot reach GitHub: …". */
    val name: String

    /**
     * The latest release the source offers, or why it cannot say (an error status, an unreadable answer).
     *
     * @throws IOException when the source cannot be reached; [java.net.http.HttpTimeoutException] when it does not
     *   answer within [UPDATE_TIMEOUT].
     */
    fun latest(): LatestRelease
}

/** What an [UpdateSource] says about its latest release. */
sealed interface LatestRelease {
    /**
     * The latest [version] (as installed: a leading `v` of a tag already dropped), its [releaseNotes] page, whether it
     * is a [prerelease] (never offered), and [asset], which finds its file for a platform: called only when the
     * version is newer than the installed one, on the same thread, and may block and throw like [UpdateSource.latest].
     */
    class Release(
        val version: String,
        val releaseNotes: URI?,
        val prerelease: Boolean = false,
        val asset: (platform: String) -> PlatformAsset,
    ) : LatestRelease

    /** The source answered, but not with a usable release; [message] says why, for the user. */
    data class Failed(
        val message: String,
    ) : LatestRelease
}

/** A newer release's file for this platform, as [LatestRelease.Release.asset] finds it. */
sealed interface PlatformAsset {
    data class Found(
        val asset: ToolAsset,
    ) : PlatformAsset

    /** The release has no build for this platform. */
    data object Missing : PlatformAsset

    /** The release lists the file without what an install needs; [message] says what, for the user. */
    data class Failed(
        val message: String,
    ) : PlatformAsset
}

/** What a check for a newer version of a server tool found. */
sealed interface UpdateCheck {
    /** No release is newer than [version], the installed one (or the pinned one when none is installed). */
    data class UpToDate(
        val version: String,
    ) : UpdateCheck

    /**
     * [version] is newer: [release] installs it on this platform; [newerThanPinned] when it is also newer than the
     * version this Thoreau was tested with; [releaseNotes] is its page.
     */
    data class Available(
        val version: String,
        val release: ToolRelease,
        val newerThanPinned: Boolean,
        val releaseNotes: URI?,
    ) : UpdateCheck

    /** [version] is newer but has no build for this platform. */
    data class NoBuild(
        val version: String,
        val releaseNotes: URI?,
    ) : UpdateCheck

    /** The check failed; [message] says why, for the user. */
    data class Failed(
        val message: String,
    ) : UpdateCheck
}

/** An HTTP answer: its status, its headers (names in lower case, the first value of each) and the body. */
data class HttpAnswer(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * One `GET` of [uri] as `User-Agent: Thoreau` with [headers], following redirects, waiting at most [UPDATE_TIMEOUT]
 * to connect and for the answer; blocking.
 *
 * @throws IOException when it cannot be sent or is interrupted; [java.net.http.HttpTimeoutException] on the timeout.
 */
fun fetchText(
    uri: URI,
    headers: Map<String, String> = emptyMap(),
): HttpAnswer {
    HttpClient
        .newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(UPDATE_TIMEOUT.toJavaDuration())
        .build()
        .use { client ->
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .header("User-Agent", "Thoreau")
                    .timeout(UPDATE_TIMEOUT.toJavaDuration())
                    .GET()
                    .build()
            val response =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofString())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted", e)
                }
            val answerHeaders =
                response
                    .headers()
                    .map()
                    .mapKeys { it.key.lowercase(Locale.ROOT) }
                    .mapValues { it.value.firstOrNull().orEmpty() }
            return HttpAnswer(response.statusCode(), answerHeaders, response.body())
        }
}

/** [text] as an `https` URI with a host, or null. */
fun webPage(text: String): URI? =
    try {
        URI(text).takeIf { it.scheme == "https" && it.host != null }
    } catch (_: URISyntaxException) {
        null
    }

/**
 * Compares two versions dot segment by dot segment: the leading digits of a segment as a number, then the rest as
 * text, where nothing comes after something (`1.0` > `1.0-rc1`); a missing segment counts as `0`.
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
