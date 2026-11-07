package dev.sluide.python

import dev.sluide.localmodels.ToolInstaller
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The smoke test against shell scripts that play ty (Linux and macOS), and against a real ty if there is one. */
class TySmokeTestTest {
    @TempDir
    lateinit var dir: Path

    /** The real timeouts for scripts that pass or fail at once (slow CI runners start shells slowly too). */
    private val smokeTest = TySmokeTest()

    /** Short timeouts for the scripts that hang: they are killed after them. */
    private val hanging = TySmokeTest(versionTimeout = 500.milliseconds, handshakeTimeout = 500.milliseconds)

    @BeforeEach
    fun posixOnly() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "the fake ty is a shell script")
    }

    private fun fakeTy(
        version: String,
        server: String,
    ): Path {
        val script = dir.resolve("ty")
        script.writeText(
            """
            |#!/bin/sh
            |case "${'$'}1" in
            |  version) $version ;;
            |  server) $server ;;
            |esac
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private fun answer(body: String) =
        "body='$body'; printf 'Content-Length: %s\\r\\n\\r\\n%s' \"${'$'}{#body}\" \"${'$'}body\""

    @Test
    fun `a ty that prints its version and answers initialize works`() {
        val ty =
            fakeTy(
                "echo 'ty 0.0.99 (1234abcd 2026-11-06)'",
                answer("""{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""),
            )

        assertNull(smokeTest.check(ty))
    }

    @Test
    fun `log lines, progress and server requests before the answer are skipped`() {
        val log = answer("""{"jsonrpc":"2.0","method":"window/logMessage","params":{"type":3,"message":"hi"}}""")
        val request =
            answer("""{"jsonrpc":"2.0","id":1,"method":"window/workDoneProgress/create","params":{"token":"t"}}""")
        val result = answer("""{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}""")
        val ty = fakeTy("echo 'ty 0.0.99'", "$log; $request; $result")

        assertNull(smokeTest.check(ty))
    }

    @Test
    fun `the server's input stays open until it answered`() {
        // Like ty: the server ends as soon as its input ends, and answers only a moment after the request. The input
        // goes to the background job through fd 3: sh gives background jobs /dev/null as their standard input.
        val endsWithInput = "exec 3<&0; ( cat <&3 > /dev/null; kill ${'$'}${'$'} 2> /dev/null ) & sleep 0.3"
        val ty = fakeTy("echo 'ty 0.0.99'", "$endsWithInput; ${answer("""{"jsonrpc":"2.0","id":1,"result":{}}""")}")

        assertNull(smokeTest.check(ty))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "echo 'Python 3.12.1'|`ty version` printed \"Python 3.12.1\", not a ty version",
            "echo 'ty 1'; exit 3|`ty version` ended with exit code 3",
            "exec sleep 30|`ty version` did not answer within 500 ms",
        ],
    )
    fun `a version that is not ty's fails before the server is started`(
        version: String,
        reason: String,
    ) {
        val started = dir.resolve("server-started")
        val ty = fakeTy(version, "touch '$started'")

        assertEquals(reason, (if (version.startsWith("exec sleep")) hanging else smokeTest).check(ty))
        assertFalse(Files.exists(started), "the handshake did not run")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "exec sleep 30|`ty server` did not answer within 500 ms",
            "exit 0|`ty server` gave no answer: the output ended",
            "ERROR|`ty server` gave no initialize result",
            "WRONG_ID|`ty server` gave no answer: the output ended",
            "NOT_JSON|`ty server` gave no answer: a message is not JSON",
        ],
    )
    fun `a server that does not answer initialize fails`(
        server: String,
        reason: String,
    ) {
        val script =
            when (server) {
                "ERROR" -> answer("""{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"no"}}""")
                "WRONG_ID" -> answer("""{"jsonrpc":"2.0","id":7,"result":{}}""")
                "NOT_JSON" -> answer("hello")
                else -> server
            }
        val ty = fakeTy("echo 'ty 0.0.99'", script)

        assertEquals(reason, (if (server.startsWith("exec sleep")) hanging else smokeTest).check(ty))
    }

    @Test
    fun `an executable that cannot start fails with the reason`() {
        val reason = smokeTest.check(dir.resolve("missing"))

        assertTrue(reason?.startsWith("cannot start it: ") == true, reason)
    }

    @Test
    fun `a message is read by its Content-Length, headers in any case`() {
        val input = ByteArrayInputStream("content-length: 2\r\nContent-Type: x\r\n\r\n{}tail".toByteArray())

        assertEquals("{}", TySmokeTest.readMessage(input))
    }

    @Test
    fun `a message without Content-Length or with a short body is refused`() {
        assertFailsWith<IOException> { TySmokeTest.readMessage(ByteArrayInputStream("X: 1\r\n\r\n{}".toByteArray())) }
        assertFailsWith<IOException> {
            TySmokeTest.readMessage(ByteArrayInputStream("Content-Length: 9\r\n\r\n{}".toByteArray()))
        }
    }

    @Test
    fun `timeouts are described in milliseconds below a second`() {
        assertEquals("500 ms", TySmokeTest.describe(500.milliseconds))
        assertEquals("5 s", TySmokeTest.describe(5.seconds))
    }

    /**
     * The real ty: the one in `SLUIDE_TEST_TY`, else the one Thoreau installed under `~/.sluide/tools/ty`; skipped
     * without either.
     */
    @Test
    fun `the real ty passes`() {
        val ty =
            System.getenv("SLUIDE_TEST_TY")?.let(Path::of)
                ?: ToolInstaller(
                    TyInstaller.DEFAULT_ROOT,
                    { null },
                    { _, _ -> "" },
                ).use { it.current.get()?.executable }
        assumeTrue(ty != null && Files.isExecutable(ty), "no ty installed")

        assertNull(TySmokeTest().check(checkNotNull(ty)))
    }
}
