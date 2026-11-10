package dev.sluide.python

import dev.sluide.localmodels.ToolInstaller
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ty's smoke test: `ty version` and `ty server` against a shell script that plays ty (Linux and macOS), and the real
 * ty if there is one; the smoke test's rules are in `LspSmokeTestTest` (server-tools).
 */
class TySmokeTestTest {
    @TempDir
    lateinit var dir: Path

    private val smokeTest = TyTool.tool(platform = "linux-aarch64").smokeTest()

    private fun fakeTy(version: String): Path {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "the fake ty is a shell script")
        val body = """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""
        val script = dir.resolve("ty")
        script.writeText(
            """
            |#!/bin/sh
            |case "${'$'}*" in
            |  version) $version ;;
            |  server) body='$body'; printf 'Content-Length: %s\r\n\r\n%s' "${'$'}{#body}" "${'$'}body" ;;
            |esac
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "echo 'ty 0.0.99 (1234abcd 2026-11-06)'|",
            "echo 'Python 3.12.1'|`ty version` printed \"Python 3.12.1\", not a ty version",
        ],
    )
    fun `ty is checked with ty version, which must print ty, and ty server`(
        version: String,
        reason: String?,
    ) {
        assertEquals(reason, smokeTest.check(fakeTy(version)))
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
                    TyTool.DEFAULT_ROOT,
                    { null },
                    { _, _ -> "" },
                ).use { it.current.get()?.executable }
        assumeTrue(ty != null && Files.isExecutable(ty), "no ty installed")

        assertNull(smokeTest.check(checkNotNull(ty)))
    }
}
