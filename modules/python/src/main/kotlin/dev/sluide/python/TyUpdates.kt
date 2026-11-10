package dev.sluide.python

import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.LatestRelease
import dev.sluide.servertools.PlatformAsset
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ToolAsset
import dev.sluide.servertools.ToolPin
import dev.sluide.servertools.ToolUpdates
import dev.sluide.servertools.UpdateSource
import dev.sluide.servertools.fetchText
import dev.sluide.servertools.webPage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executor

/** The latest release of ty on GitHub (prereleases and drafts are never "latest"). */
private val LATEST = URI.create("https://api.github.com/repos/astral-sh/ty/releases/latest")

/** Where ty's release assets are downloaded from on github.com. */
private const val RELEASE_DOWNLOADS = "/astral-sh/ty/releases/download/"

private val DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

/**
 * Checks GitHub for a newer ty, only when the user asks (Check for Updates; ADR 0144, no background fetch): one `GET`
 * of the latest release from the GitHub API with a 15 s timeout, off the FX thread. A newer version comes with the
 * release of this platform's asset, its size and SHA-256 from GitHub's `digest`.
 */
@Component
class TyUpdates : ToolUpdates {
    @Autowired
    constructor() : this(TyTool.tool(), { fetchLatest() })

    private constructor(tool: ServerTool, fetch: () -> HttpAnswer) : super(tool, GitHubReleases(tool.pin, fetch))

    internal constructor(
        tool: ServerTool,
        fetch: () -> HttpAnswer,
        executor: Executor,
        dispatch: (Runnable) -> Unit,
    ) : super(tool, GitHubReleases(tool.pin, fetch), executor, dispatch)

    internal companion object {
        /** The latest release from the GitHub API: JSON, without sign-in. */
        fun fetchLatest(uri: URI = LATEST): HttpAnswer =
            fetchText(uri, mapOf("Accept" to "application/vnd.github+json"))

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
    }
}

/**
 * ty's releases on GitHub: the latest release's `tag_name` (a leading `v` dropped), `prerelease` and `html_url`, and
 * the asset with the pinned asset name of a platform, with its `size`, `digest` and a download URL on github.com below
 * ty's releases.
 */
private class GitHubReleases(
    private val pin: ToolPin,
    private val fetch: () -> HttpAnswer,
) : UpdateSource {
    override val name = "GitHub"

    override fun latest(): LatestRelease {
        val answer = fetch()
        if (answer.status != 200) return LatestRelease.Failed(TyUpdates.statusMessage(answer))
        val json =
            try {
                JsonMapper.shared().readTree(answer.body)
            } catch (_: JacksonException) {
                return LatestRelease.Failed("GitHub's answer is not readable")
            }
        val version = json.path("tag_name").asString("").removePrefix("v")
        val notes = webPage(json.path("html_url").asString(""))
        return LatestRelease.Release(version, notes, json.path("prerelease").asBoolean(false)) { platform ->
            asset(json, version, platform)
        }
    }

    private fun asset(
        json: JsonNode,
        version: String,
        platform: String,
    ): PlatformAsset {
        val assetName = pin.asset(platform)?.name ?: return PlatformAsset.Missing
        val asset =
            json.path("assets").firstOrNull { it.path("name").asString("") == assetName }
                ?: return PlatformAsset.Missing
        val bytes = asset.path("size").asLong(0)
        val sha256 = DIGEST.matchEntire(asset.path("digest").asString(""))?.groupValues?.get(1)
        val url =
            webPage(asset.path("browser_download_url").asString(""))?.takeIf {
                it.host == "github.com" && it.path.startsWith(RELEASE_DOWNLOADS)
            }
        if (sha256 == null || bytes <= 0 || url == null) {
            return PlatformAsset.Failed("GitHub lists $assetName of ty $version without its size or checksum")
        }
        return PlatformAsset.Found(ToolAsset(assetName, url, bytes, Checksum.Sha256(sha256.lowercase(Locale.ROOT))))
    }
}
