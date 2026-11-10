package dev.sluide.typescript

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.UpdateCheck
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The update check against fake registry answers shaped like the real ones of 2026-11-10 (trimmed to the fields
 * read); the comparison with the installed version and the pin is tested in server-tools (`ToolUpdatesTest`).
 */
class NpmUpdatesTest {
    @TempDir
    lateinit var dir: Path

    private val registry = "https://registry.npmjs.org/"
    private val tarball = "$registry@typescript/typescript-linux-arm64/-/typescript-linux-arm64-7.0.2.tgz"
    private val requests = mutableListOf<Pair<URI, Map<String, String>>>()

    private fun json(body: String) = HttpAnswer(200, emptyMap(), body)

    private fun latest(version: String = "7.0.2") = json("""{"latest":"$version","beta":"7.1.0-beta"}""")

    private fun metadata(
        tarballUrl: String = tarball,
        integrity: String = ARM64_INTEGRITY,
    ) = json(
        """{"name":"@typescript/typescript-linux-arm64","dist":{"tarball":"$tarballUrl","integrity":"$integrity"}}""",
    )

    private fun ranged(contentRange: String? = "bytes 0-0/8900516") =
        HttpAnswer(206, listOfNotNull(contentRange?.let { "content-range" to it }).toMap(), "\u001f")

    /**
     * A registry that answers per URL: [latest] for the dist-tags, [meta] for exactly the metadata URL of the
     * linux-arm64 package at 7.0.2, [range] for its tarball, 404 for anything else.
     */
    private fun registry(
        latest: HttpAnswer = latest(),
        meta: HttpAnswer = metadata(),
        range: HttpAnswer = ranged(),
    ): (URI, Map<String, String>) -> HttpAnswer =
        { uri, headers ->
            requests += uri to headers
            when {
                uri.toString() == "$registry-/package/typescript/dist-tags" -> latest
                uri.toString() == tarball -> range
                uri.toString() == "$registry@typescript/typescript-linux-arm64/7.0.2" -> meta
                else -> HttpAnswer(404, emptyMap(), "")
            }
        }

    private fun check(
        fetch: (URI, Map<String, String>) -> HttpAnswer,
        installed: String = "7.0.1",
    ): UpdateCheck {
        var result: UpdateCheck? = null
        TypeScriptUpdates(
            TypeScriptFakes(dir).tool,
            fetch,
            Runnable::run,
            { it.run() },
        ).check(installed) { result = it }
        return checkNotNull(result)
    }

    @Test
    fun `a newer release comes with the platform package's tarball, its integrity and its size from the range`() {
        val available = assertIs<UpdateCheck.Available>(check(registry()))

        assertEquals("7.0.2", available.version)
        assertEquals(URI("https://www.npmjs.com/package/typescript/v/7.0.2"), available.releaseNotes)
        val release = available.release
        assertEquals(URI(tarball), release.url)
        assertEquals(8_900_516, release.bytes)
        assertEquals<Checksum?>(Checksum.sri(ARM64_INTEGRITY), release.checksum)
        assertEquals(ArchiveFormat.TAR_GZ, release.format)
        assertEquals("package/lib/tsc", release.executable)
        assertEquals(
            listOf("Range" to "bytes=0-0"),
            requests
                .last()
                .second
                .filterKeys { it == "Range" }
                .toList(),
        )
        assertEquals(
            listOf(
                "$registry-/package/typescript/dist-tags",
                "$registry@typescript/typescript-linux-arm64/7.0.2",
                tarball,
            ),
            requests.map { it.first.toString() },
        )
    }

    @Test
    fun `an equal or older latest version is up to date and asks nothing about the platform`() {
        assertEquals(UpdateCheck.UpToDate("7.0.2"), check(registry(), installed = "7.0.2"))
        assertEquals(UpdateCheck.UpToDate("7.1.0"), check(registry(latest = latest("7.0.2")), installed = "7.1.0"))
        assertEquals(listOf(URI("$registry-/package/typescript/dist-tags")), requests.map { it.first }.distinct())
    }

    @Test
    fun `a newer release whose platform package has no such version has no build`() {
        val notes = URI("https://www.npmjs.com/package/typescript/v/7.0.2")

        assertEquals(
            UpdateCheck.NoBuild("7.0.2", notes),
            check(registry(meta = HttpAnswer(404, emptyMap(), ""))),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.com/@typescript/typescript-linux-arm64/-/typescript-linux-arm64-7.0.2.tgz",
            "http://registry.npmjs.org/@typescript/typescript-linux-arm64/-/typescript-linux-arm64-7.0.2.tgz",
            "https://registry.npmjs.org/@typescript/typescript-linux-x64/-/typescript-linux-x64-7.0.2.tgz",
            "https://registry.npmjs.org/@other/typescript-linux-arm64/-/typescript-linux-arm64-7.0.2.tgz",
            "https://registry.npmjs.org/@typescript/typescript-linux-arm64/-/typescript-linux-arm64-7.0.3.tgz",
            "https://registry.npmjs.org.evil.test/@typescript/typescript-linux-arm64/-/typescript-linux-arm64-7.0.2.tgz",
        ],
    )
    fun `a tarball outside the registry's package path is refused`(url: String) {
        // The listed integrity must belong to exactly the tarball Thoreau downloads (the expected URL), not to a file
        // the metadata names elsewhere; the ranged request alone would succeed for any of these.
        assertEquals(
            UpdateCheck.Failed("npm lists typescript-linux-arm64-7.0.2.tgz without its tarball or integrity"),
            check(registry(meta = metadata(tarballUrl = url))),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "sha1-abc", "sha512-short", "sha512-AAAA sha256-BBBB"])
    fun `an integrity that is not one sha512 value is refused`(integrity: String) {
        assertEquals(
            UpdateCheck.Failed("npm lists typescript-linux-arm64-7.0.2.tgz without its tarball or integrity"),
            check(registry(meta = metadata(integrity = integrity))),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["bytes 0-0/*", "bytes 0-0/", "bytes 0-0/0", "bytes 0-0/12x", "garbled", "bytes 5-9/100"])
    fun `a missing or garbled Content-Range is a readable failure`(contentRange: String) {
        val message = "npm did not say how large typescript-linux-arm64-7.0.2.tgz is"

        assertEquals(UpdateCheck.Failed(message), check(registry(range = ranged(contentRange))))
    }

    @Test
    fun `a ranged answer without Content-Range, or the whole file with status 200, is a readable failure`() {
        val message = "npm did not say how large typescript-linux-arm64-7.0.2.tgz is"

        assertEquals(UpdateCheck.Failed(message), check(registry(range = ranged(null))))
        assertEquals(UpdateCheck.Failed(message), check(registry(range = HttpAnswer(200, emptyMap(), "whole file"))))
    }

    @Test
    fun `answers that are not usable are readable failures`() {
        assertEquals(UpdateCheck.Failed("npm's answer is not readable"), check(registry(latest = json("<html>"))))
        assertEquals(
            UpdateCheck.Failed("npm's latest TypeScript release has no usable version"),
            check(registry(latest = json("{}"))),
        )
        assertEquals(
            UpdateCheck.Failed("npm has no TypeScript package"),
            check(registry(latest = HttpAnswer(404, emptyMap(), ""))),
        )
        assertEquals(
            UpdateCheck.Failed("npm answered with HTTP status 502"),
            check(registry(latest = HttpAnswer(502, emptyMap(), ""))),
        )
        assertEquals(
            UpdateCheck.Failed("npm answered with HTTP status 503"),
            check(registry(meta = HttpAnswer(503, emptyMap(), ""))),
        )
        assertEquals(
            UpdateCheck.Failed("npm lists typescript-linux-arm64-7.0.2.tgz without its tarball or integrity"),
            check(registry(meta = json("[]"))),
        )
    }

    @Test
    fun `a check without the registry names npm, also when only the tarball request fails`() {
        val fromTarball: (URI, Map<String, String>) -> HttpAnswer = { uri, headers ->
            if (uri.path.endsWith(".tgz")) throw HttpTimeoutException("timed out") else registry()(uri, headers)
        }

        assertEquals(
            UpdateCheck.Failed("Cannot reach npm: connection refused"),
            check({ _, _ -> throw IOException("connection refused") }),
        )
        assertEquals(
            UpdateCheck.Failed("npm did not answer within 15 s"),
            check({ _, _ -> throw HttpTimeoutException("timed out") }),
        )
        assertEquals(UpdateCheck.Failed("npm did not answer within 15 s"), check(fromTarball))
    }
}
