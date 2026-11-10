package dev.sluide.typescript

import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.LatestRelease
import dev.sluide.servertools.PlatformAsset
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ToolAsset
import dev.sluide.servertools.ToolUpdates
import dev.sluide.servertools.UpdateSource
import dev.sluide.servertools.fetchText
import dev.sluide.servertools.webPage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.concurrent.Executor

/** One `GET` of a URI with request headers; [fetchText] in the app, a fake in tests. */
internal typealias NpmFetch = (uri: URI, headers: Map<String, String>) -> HttpAnswer

private val CONTENT_RANGE = Regex("bytes 0-0/([0-9]{1,15})")

/** The dist-tags of the `typescript` package: a few hundred bytes, where its package document is megabytes. */
private val DIST_TAGS = URI("${NPM_REGISTRY}-/package/typescript/dist-tags")

/**
 * Checks the npm registry for a newer TypeScript 7, only when the user asks (Check for Updates; ADR 0159, no
 * background fetch): `GET` requests with a 15 s timeout, off the FX thread. A newer version comes with the tarball of
 * this platform's package, its size and its integrity from the registry.
 */
@Component
class TypeScriptUpdates : ToolUpdates {
    @Autowired
    constructor() : this(TypeScriptTool.tool(), ::fetchText)

    private constructor(tool: ServerTool, fetch: NpmFetch) : super(tool, NpmUpdates(fetch))

    internal constructor(
        tool: ServerTool,
        fetch: NpmFetch,
        executor: Executor,
        dispatch: (Runnable) -> Unit,
    ) : super(tool, NpmUpdates(fetch), executor, dispatch)
}

/**
 * TypeScript's releases on the npm registry: the `latest` dist-tag of the `typescript` package, and for a platform the
 * metadata of `@typescript/typescript-<package>` at that version (`dist.tarball`, `dist.integrity`) and the size of
 * its tarball. npm publishes no size, so it is the total of a ranged `GET` of the first byte.
 */
internal class NpmUpdates(
    private val fetch: NpmFetch,
) : UpdateSource {
    override val name = "npm"

    override fun latest(): LatestRelease {
        val answer = fetch(DIST_TAGS, mapOf("Accept" to "application/json"))
        if (answer.status != 200) {
            return LatestRelease.Failed(
                if (answer.status == 404) "npm has no TypeScript package" else statusMessage(answer),
            )
        }
        val json = parse(answer.body) ?: return LatestRelease.Failed("npm's answer is not readable")
        val version = json.path("latest").asString("")
        val notes = webPage("https://www.npmjs.com/package/typescript/v/$version")
        return LatestRelease.Release(
            version,
            notes,
            prerelease = '-' in version,
        ) { platform -> asset(version, platform) }
    }

    private fun asset(
        version: String,
        platform: String,
    ): PlatformAsset {
        val pkg = PLATFORM_PACKAGES[platform] ?: return PlatformAsset.Missing
        val metadata =
            fetch(
                URI("${NPM_REGISTRY}@typescript/typescript-$pkg/$version"),
                mapOf("Accept" to "application/json"),
            )
        if (metadata.status == 404) return PlatformAsset.Missing
        if (metadata.status != 200) return PlatformAsset.Failed(statusMessage(metadata))
        val dist = parse(metadata.body)?.path("dist")
        val assetName = tarballName(pkg, version)
        val failed = PlatformAsset.Failed("npm lists $assetName without its tarball or integrity")
        if (dist == null) return failed
        val expected = tarballUrl(pkg, version)
        val tarball = webPage(dist.path("tarball").asString(""))
        val integrity = Checksum.sri(dist.path("integrity").asString(""))?.takeIf { it.wellFormed }
        // The registry's host and path decide where the download goes: nothing else is accepted.
        if (tarball != expected || integrity == null) return failed
        val ranged = fetch(expected, mapOf("Range" to "bytes=0-0", "Accept" to "application/octet-stream"))
        if (ranged.status != 206) return PlatformAsset.Failed("npm did not say how large $assetName is")
        val bytes =
            CONTENT_RANGE
                .matchEntire(ranged.headers["content-range"].orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: return PlatformAsset.Failed("npm did not say how large $assetName is")
        return PlatformAsset.Found(ToolAsset(assetName, expected, bytes, integrity))
    }

    private fun parse(body: String) =
        try {
            JsonMapper.shared().readTree(body)
        } catch (_: JacksonException) {
            null
        }

    private fun statusMessage(answer: HttpAnswer) = "npm answered with HTTP status ${answer.status}"
}
