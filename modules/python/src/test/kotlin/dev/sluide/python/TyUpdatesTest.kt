package dev.sluide.python

import com.sun.net.httpserver.HttpServer
import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The update check against GitHub answers recorded on 2026-11-07 (trimmed to the fields read and three assets). */
class TyUpdatesTest {
    @TempDir
    lateinit var dir: Path

    private val latest = resource("releases-latest.json")
    private val ok = HttpAnswer(200, emptyMap(), latest)

    private fun resource(name: String) = checkNotNull(TyUpdatesTest::class.java.getResource(name)) { name }.readText()

    private fun updates(
        pinned: String = "0.0.99",
        platform: String? = "linux-aarch64",
        fetch: () -> HttpAnswer = { ok },
    ) = TyUpdates(
        TyPin(pinned, mapOf("linux-aarch64" to TyAsset(FAKE_ASSET, 1, "b".repeat(64)))),
        platform,
        fetch,
        Runnable::run,
        { it.run() },
    )

    @Test
    fun `a newer release comes with this platform's asset, its size and GitHub's digest`() {
        val result = updates(pinned = "0.0.98").interpret(ok, "0.0.98")

        val available = assertIs<TyUpdateCheck.Available>(result)
        assertEquals("0.0.99", available.version)
        assertEquals(true, available.newerThanPinned)
        assertEquals(URI("https://github.com/astral-sh/ty/releases/tag/0.0.99"), available.releaseNotes)
        val release = available.release
        assertEquals("0.0.99", release.version)
        assertEquals(
            URI("https://github.com/astral-sh/ty/releases/download/0.0.99/ty-aarch64-unknown-linux-musl.tar.gz"),
            release.url,
        )
        assertEquals(13_659_057, release.bytes)
        assertEquals(
            Checksum.Sha256("1aeb90425e934914f437f30ec7de6f5a55352aa858628903adb08a24a852191e"),
            release.checksum,
        )
        assertEquals(ArchiveFormat.TAR_GZ, release.format)
        assertEquals("ty-aarch64-unknown-linux-musl/ty", release.executable)
    }

    @Test
    fun `a release newer than the installed one but not than the pin is not newer than tested`() {
        val result = updates(pinned = "0.0.99").interpret(ok, "0.0.97")

        assertEquals(false, assertIs<TyUpdateCheck.Available>(result).newerThanPinned)
    }

    @ParameterizedTest
    @CsvSource("0.0.99", "0.1.0", "1.0")
    fun `no newer release is up to date with the version compared`(compared: String) {
        assertEquals(TyUpdateCheck.UpToDate(compared), updates().interpret(ok, compared))
    }

    @Test
    fun `a prerelease is not offered`() {
        val answer = ok.copy(body = latest.replace("\"prerelease\": false", "\"prerelease\": true"))

        assertEquals(TyUpdateCheck.UpToDate("0.0.98"), updates().interpret(answer, "0.0.98"))
    }

    @Test
    fun `a newer release without an asset for this platform has no build`() {
        val notes = URI("https://github.com/astral-sh/ty/releases/tag/0.0.99")
        val withoutAsset = ok.copy(body = latest.replace(FAKE_ASSET, "ty-other.tar.gz"))

        assertEquals(TyUpdateCheck.NoBuild("0.0.99", notes), updates(platform = null).interpret(ok, "0.0.98"))
        assertEquals(TyUpdateCheck.NoBuild("0.0.99", notes), updates().interpret(withoutAsset, "0.0.98"))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "sha256:1aeb90425e934914f437f30ec7de6f5a55352aa858628903adb08a24a852191e|sha1:abc",
            "\"size\": 13659057|\"size\": 0",
            "https://github.com/astral-sh/ty/releases/download/0.0.99/ty-aarch64|https://example.com/ty-aarch64",
            "https://github.com/astral-sh/ty/releases/download/0.0.99/ty-aarch64|https://github.com/other/ty/releases/download/0.0.99/ty-aarch64",
        ],
    )
    fun `an asset without a usable checksum, size or GitHub URL is refused`(
        original: String,
        replacement: String,
    ) {
        val answer = ok.copy(body = latest.replace(original, replacement))

        assertEquals(
            TyUpdateCheck.Failed("GitHub lists $FAKE_ASSET of ty 0.0.99 without its size or checksum"),
            updates().interpret(answer, "0.0.98"),
        )
    }

    @Test
    fun `GitHub's rate limit is named, other failures give the status`() {
        val limited = HttpAnswer(403, mapOf("x-ratelimit-remaining" to "0"), resource("releases-rate-limited.json"))
        val tooMany = HttpAnswer(429, emptyMap(), resource("releases-rate-limited.json"))
        val limitText = "GitHub's limit of requests without sign-in is used up for now; try again in an hour"

        assertEquals(TyUpdateCheck.Failed(limitText), updates().interpret(limited, "0.0.98"))
        assertEquals(TyUpdateCheck.Failed(limitText), updates().interpret(tooMany, "0.0.98"))
        assertEquals(
            TyUpdateCheck.Failed("GitHub answered with HTTP status 403"),
            updates().interpret(HttpAnswer(403, emptyMap(), "Forbidden"), "0.0.98"),
        )
        assertEquals(
            TyUpdateCheck.Failed("GitHub answered with HTTP status 502"),
            updates().interpret(HttpAnswer(502, emptyMap(), ""), "0.0.98"),
        )
    }

    @Test
    fun `a tag with a v is the version without it, also as the folder of the install`() {
        val answer = ok.copy(body = latest.replace("\"tag_name\": \"0.0.99\"", "\"tag_name\": \"v0.1.0\""))

        val available = assertIs<TyUpdateCheck.Available>(updates().interpret(answer, "0.0.99"))
        assertEquals("0.1.0", available.version)
        assertEquals("0.1.0", available.release.version)

        val fakes = TyFakes(dir)
        val installer = fakes.installer()
        installer.update(available.release, null)
        fakes.runQueued()
        assertEquals(
            fakes.root.resolve("0.1.0/ty-aarch64-unknown-linux-musl/ty").toAbsolutePath(),
            installer.installed.get()?.executable,
        )
    }

    @Test
    fun `an upper-case digest is lower-cased`() {
        val digest = "1aeb90425e934914f437f30ec7de6f5a55352aa858628903adb08a24a852191e"
        val answer = ok.copy(body = latest.replace(digest, digest.uppercase()))

        val available = assertIs<TyUpdateCheck.Available>(updates().interpret(answer, "0.0.98"))
        assertEquals(Checksum.Sha256(digest), available.release.checksum)
    }

    @Test
    fun `an answer that is not a release is refused`() {
        assertEquals(
            TyUpdateCheck.Failed("GitHub's answer is not readable"),
            updates().interpret(ok.copy(body = "<html>"), "0.0.98"),
        )
        assertEquals(
            TyUpdateCheck.Failed("GitHub's latest ty release has no usable version"),
            updates().interpret(ok.copy(body = """{"tag_name": "../x"}"""), "0.0.98"),
        )
        assertEquals(
            TyUpdateCheck.Failed("GitHub's latest ty release has no usable version"),
            updates().interpret(ok.copy(body = "[]"), "0.0.98"),
        )
    }

    @Test
    fun `a check compares with the installed version, else the pin, and fails readably without GitHub`() {
        val results = mutableListOf<TyUpdateCheck>()
        updates(pinned = "0.0.99").check(null, results::add)
        updates().check("0.0.98", results::add)
        updates { throw IOException("connection refused") }.check("0.0.98", results::add)
        updates { throw HttpTimeoutException("timed out") }.check("0.0.98", results::add)
        updates { error("broken") }.check("0.0.98", results::add)

        assertEquals(TyUpdateCheck.UpToDate("0.0.99"), results[0])
        assertIs<TyUpdateCheck.Available>(results[1])
        assertEquals(TyUpdateCheck.Failed("Cannot reach GitHub: connection refused"), results[2])
        assertEquals(TyUpdateCheck.Failed("GitHub did not answer within 15 s"), results[3])
        assertEquals(TyUpdateCheck.Failed("The check failed: broken"), results[4])
    }

    @Test
    fun `the request asks for GitHub's JSON as Thoreau and reads status, headers and body`() {
        val requests = mutableListOf<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/repos/astral-sh/ty/releases/latest") { exchange ->
            requests += exchange.requestHeaders.getFirst("Accept") to exchange.requestHeaders.getFirst("User-Agent")
            val body = "{}".toByteArray()
            exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val uri = URI("http://127.0.0.1:${server.address.port}/repos/astral-sh/ty/releases/latest")

            val answer = TyUpdates.fetchLatest(uri)

            assertEquals(HttpAnswer(403, answer.headers, "{}"), answer)
            assertEquals("0", answer.headers["x-ratelimit-remaining"])
            assertEquals(listOf<Pair<String?, String?>>("application/vnd.github+json" to "Thoreau"), requests)
        } finally {
            server.stop(0)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "0.0.99, 0.0.98, 1",
        "0.0.100, 0.0.99, 1",
        "0.1.0, 0.0.99, 1",
        "1.0, 0.99.99, 1",
        "0.1, 0.1.0, 0",
        "0.0.99, 0.0.99, 0",
        "0.1.0, 0.1.0-rc1, 1",
        "0.1.0-rc2, 0.1.0-rc1, 1",
        "10.0.0, 9.0.0, 1",
        "0.0.98, 0.0.99, -1",
        "12345678901234567890.0, 1.0, 1",
    )
    fun `versions compare by number per dot segment`(
        a: String,
        b: String,
        expected: Int,
    ) {
        assertEquals(expected, Integer.signum(TyUpdates.compareVersions(a, b)))
        assertEquals(-expected, Integer.signum(TyUpdates.compareVersions(b, a)))
    }
}
