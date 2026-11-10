package dev.sluide.python

import com.sun.net.httpserver.HttpServer
import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.UpdateCheck
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

/**
 * The update check against GitHub answers recorded on 2026-11-07 (trimmed to the fields read and three assets); the
 * comparison with the installed version and the pin is tested in server-tools (`ToolUpdatesTest`).
 */
class TyUpdatesTest {
    @TempDir
    lateinit var dir: Path

    private val latest = resource("releases-latest.json")
    private val ok = HttpAnswer(200, emptyMap(), latest)

    private fun resource(name: String) = checkNotNull(TyUpdatesTest::class.java.getResource(name)) { name }.readText()

    private fun updates(fetch: () -> HttpAnswer = { ok }) =
        TyUpdates(TyFakes(dir).tool, fetch, Runnable::run, { it.run() })

    /** The check's answer for [answer], compared with [compared]. */
    private fun interpret(
        answer: HttpAnswer,
        compared: String = "0.0.98",
    ): UpdateCheck {
        var result: UpdateCheck? = null
        updates { answer }.check(compared) { result = it }
        return checkNotNull(result)
    }

    @Test
    fun `a newer release comes with this platform's asset, its size and GitHub's digest`() {
        val available = assertIs<UpdateCheck.Available>(interpret(ok))

        assertEquals("0.0.99", available.version)
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
    fun `a prerelease is not offered`() {
        val answer = ok.copy(body = latest.replace("\"prerelease\": false", "\"prerelease\": true"))

        assertEquals(UpdateCheck.UpToDate("0.0.98"), interpret(answer))
    }

    @Test
    fun `a newer release without the pinned asset name has no build`() {
        val notes = URI("https://github.com/astral-sh/ty/releases/tag/0.0.99")
        val withoutAsset = ok.copy(body = latest.replace(FAKE_ASSET, "ty-other.tar.gz"))

        assertEquals(UpdateCheck.NoBuild("0.0.99", notes), interpret(withoutAsset))
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
            UpdateCheck.Failed("GitHub lists $FAKE_ASSET of ty 0.0.99 without its size or checksum"),
            interpret(answer),
        )
    }

    @Test
    fun `GitHub's rate limit is named, other failures give the status`() {
        val limited = HttpAnswer(403, mapOf("x-ratelimit-remaining" to "0"), resource("releases-rate-limited.json"))
        val tooMany = HttpAnswer(429, emptyMap(), resource("releases-rate-limited.json"))
        val limitText = "GitHub's limit of requests without sign-in is used up for now; try again in an hour"

        assertEquals(UpdateCheck.Failed(limitText), interpret(limited))
        assertEquals(UpdateCheck.Failed(limitText), interpret(tooMany))
        assertEquals(
            UpdateCheck.Failed("GitHub answered with HTTP status 403"),
            interpret(HttpAnswer(403, emptyMap(), "Forbidden")),
        )
        assertEquals(
            UpdateCheck.Failed("GitHub answered with HTTP status 502"),
            interpret(HttpAnswer(502, emptyMap(), "")),
        )
        assertEquals(UpdateCheck.Failed("GitHub has no ty release"), interpret(HttpAnswer(404, emptyMap(), "")))
    }

    @Test
    fun `a tag with a v is the version without it, also as the folder of the install`() {
        val answer = ok.copy(body = latest.replace("\"tag_name\": \"0.0.99\"", "\"tag_name\": \"v0.1.0\""))

        val available = assertIs<UpdateCheck.Available>(interpret(answer, "0.0.99"))
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

        val available = assertIs<UpdateCheck.Available>(interpret(answer))
        assertEquals(Checksum.Sha256(digest), available.release.checksum)
    }

    @Test
    fun `an answer that is not a release is refused`() {
        assertEquals(UpdateCheck.Failed("GitHub's answer is not readable"), interpret(ok.copy(body = "<html>")))
        assertEquals(
            UpdateCheck.Failed("GitHub's latest ty release has no usable version"),
            interpret(ok.copy(body = """{"tag_name": "../x"}""")),
        )
        assertEquals(
            UpdateCheck.Failed("GitHub's latest ty release has no usable version"),
            interpret(ok.copy(body = "[]")),
        )
    }

    @Test
    fun `a check without GitHub names it`() {
        val results = mutableListOf<UpdateCheck>()
        updates { throw IOException("connection refused") }.check("0.0.98", results::add)
        updates { throw HttpTimeoutException("timed out") }.check("0.0.98", results::add)

        assertEquals(
            listOf<UpdateCheck>(
                UpdateCheck.Failed("Cannot reach GitHub: connection refused"),
                UpdateCheck.Failed("GitHub did not answer within 15 s"),
            ),
            results,
        )
    }

    @Test
    fun `the request asks for GitHub's JSON`() {
        val requests = mutableListOf<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/repos/astral-sh/ty/releases/latest") { exchange ->
            requests += exchange.requestHeaders.getFirst("Accept") to exchange.requestHeaders.getFirst("User-Agent")
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val uri = URI("http://127.0.0.1:${server.address.port}/repos/astral-sh/ty/releases/latest")

            val answer = TyUpdates.fetchLatest(uri)

            assertEquals(HttpAnswer(200, answer.headers, "{}"), answer)
            assertEquals(listOf<Pair<String?, String?>>("application/vnd.github+json" to "Thoreau"), requests)
        } finally {
            server.stop(0)
        }
    }
}
