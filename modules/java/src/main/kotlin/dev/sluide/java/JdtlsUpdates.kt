package dev.sluide.java

import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.LatestRelease
import dev.sluide.servertools.PlatformAsset
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ToolAsset
import dev.sluide.servertools.ToolUpdates
import dev.sluide.servertools.UpdateSource
import dev.sluide.servertools.compareVersions
import dev.sluide.servertools.fetchHead
import dev.sluide.servertools.fetchText
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executor

/** One `GET` of a URI with request headers; [fetchText] in the app, a fake in tests. */
internal typealias MilestoneFetch = (uri: URI, headers: Map<String, String>) -> HttpAnswer

/**
 * Checks `download.eclipse.org` for a newer jdtls milestone, only when the user asks (Check for Updates; ADR 0159,
 * ADR 0179, no background fetch): `GET` requests (a `HEAD` for the size of the archive) with a 15 s timeout, off the
 * FX thread.
 */
@Component
class JdtlsUpdates : ToolUpdates {
    @Autowired
    constructor() : this(JdtlsTool.tool(), ::fetchText, ::fetchHead)

    private constructor(tool: ServerTool, fetch: MilestoneFetch, head: MilestoneFetch) :
        super(tool, EclipseMilestones(fetch, head))

    internal constructor(
        tool: ServerTool,
        fetch: MilestoneFetch,
        head: MilestoneFetch,
        executor: Executor,
        dispatch: (Runnable) -> Unit,
    ) : super(tool, EclipseMilestones(fetch, head), executor, dispatch)
}

private const val MAX_INDEX_CHARS = 2_000_000
private const val MILESTONES_PATH = "/jdtls/milestones/"
private val CONTENT_LENGTH = Regex("[0-9]{1,15}")
private val SHA256 = Regex("[0-9A-Fa-f]{64}")

/**
 * jdtls's milestones on download.eclipse.org: the newest version folder of the index (an HTML table, one row per
 * folder, in text order, so versions are compared as numbers), the archive `<version>/latest.txt` names, its
 * `.sha256` (the bare hash) and its size (the `content-length` of a `HEAD`). The same archive serves
 * every platform. No release notes: the version page's links go to mirrors.
 */
internal class EclipseMilestones(
    private val fetch: MilestoneFetch,
    private val head: MilestoneFetch,
) : UpdateSource {
    override val name = "download.eclipse.org"

    override fun latest(): LatestRelease {
        val answer = fetch(URI(MILESTONES), mapOf("Accept" to "text/html"))
        if (answer.status != 200) return LatestRelease.Failed(statusMessage(answer))
        val version =
            versions(answer.body).maxWithOrNull(::compareVersions)
                ?: return LatestRelease.Failed("$name lists no jdtls version")
        return LatestRelease.Release(version, releaseNotes = null) { _ -> asset(version) }
    }

    /** The versions of the index's links that are exactly `/jdtls/milestones/<version>`; others are ignored. */
    private fun versions(html: String): List<String> =
        Jsoup
            .parse(html.take(MAX_INDEX_CHARS))
            .select("a[href]")
            .map { it.attr("href") }
            .filter { it.startsWith(MILESTONES_PATH) }
            .map { it.removePrefix(MILESTONES_PATH) }
            .filter { isVersion(it) }

    private fun asset(version: String): PlatformAsset {
        val latest = fetch(URI("$MILESTONES$version/latest.txt"), mapOf("Accept" to "text/plain"))
        if (latest.status == 404) return PlatformAsset.Failed("$name has no latest.txt for jdtls $version")
        if (latest.status != 200) return PlatformAsset.Failed(statusMessage(latest))
        val assetName =
            latest.body
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.takeIf { isArchiveOf(version, it) }
                ?: return PlatformAsset.Failed("$name's latest.txt of jdtls $version does not name its archive")
        val url = milestoneUrl(version, assetName)
        val checksum = fetch(URI("$url.sha256"), mapOf("Accept" to "text/plain"))
        if (checksum.status == 404) return PlatformAsset.Failed("$name has no sha256 for $assetName")
        if (checksum.status != 200) return PlatformAsset.Failed(statusMessage(checksum))
        val hash =
            checksum.body.trim().takeIf { SHA256.matches(it) }
                ?: return PlatformAsset.Failed("$name's sha256 of $assetName is not a hash")
        // HEAD, not a ranged GET: a host that ignores Range would send the 51 MB body, which the fetch reads as text.
        val sized = head(url, emptyMap())
        val bytes =
            CONTENT_LENGTH
                .matchEntire(sized.headers["content-length"].orEmpty())
                ?.value
                ?.toLongOrNull()
                ?.takeIf { sized.status == 200 && it > 0 }
                ?: return PlatformAsset.Failed("$name did not say how large $assetName is")
        return PlatformAsset.Found(ToolAsset(assetName, url, bytes, Checksum.Sha256(hash.lowercase(Locale.ROOT))))
    }

    private fun statusMessage(answer: HttpAnswer) = "$name answered with HTTP status ${answer.status}"
}
