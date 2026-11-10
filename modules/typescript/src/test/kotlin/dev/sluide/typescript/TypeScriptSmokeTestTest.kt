package dev.sluide.typescript

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
 * The TypeScript server's smoke test: `tsc --version` and `tsc --lsp --stdio` against a shell script that plays tsc
 * (Linux and macOS), and the real tsc if there is one; the smoke test's rules are in `LspSmokeTestTest` (server-tools).
 */
class TypeScriptSmokeTestTest {
    @TempDir
    lateinit var dir: Path

    private val smokeTest = TypeScriptTool.tool(platform = "linux-aarch64").smokeTest()

    private fun fakeTsc(version: String): Path {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "the fake tsc is a shell script")
        val body = """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""
        val script = dir.resolve("tsc")
        script.writeText(
            """
            |#!/bin/sh
            |case "${'$'}*" in
            |  --version) $version ;;
            |  "--lsp --stdio") body='$body'; printf 'Content-Length: %s\r\n\r\n%s' "${'$'}{#body}" "${'$'}body" ;;
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
            "echo 'Version 7.0.2'|",
            "echo 'v22.1.0'|`tsc --version` printed \"v22.1.0\", not a TypeScript version",
        ],
    )
    fun `tsc is checked with tsc --version, which must print Version, and tsc --lsp --stdio`(
        version: String,
        reason: String?,
    ) {
        assertEquals(reason, smokeTest.check(fakeTsc(version)))
    }

    /**
     * The real tsc: the one in `SLUIDE_TEST_TSC`, else the one Thoreau installed under `~/.sluide/tools/typescript`;
     * skipped without either.
     */
    @Test
    fun `the real tsc passes`() {
        val tsc =
            System.getenv("SLUIDE_TEST_TSC")?.let(Path::of)
                ?: ToolInstaller(
                    TypeScriptTool.DEFAULT_ROOT,
                    { null },
                    { _, _ -> "" },
                ).use { it.current.get()?.executable }
        assumeTrue(tsc != null && Files.isExecutable(tsc), "no tsc installed")

        assertNull(smokeTest.check(checkNotNull(tsc)))
    }
}
