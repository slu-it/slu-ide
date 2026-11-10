package dev.sluide.typescript

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.ToolPin
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pin resource of the TypeScript server and how its release is fetched and unpacked. */
class TypeScriptToolTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the pin resource names a version and the tarball of every Thoreau platform`() {
        val pin = TypeScriptTool.pin()

        assertEquals("7.0.2", pin.version)
        val expectedNames =
            mapOf(
                "linux-x64" to "typescript-linux-x64-7.0.2.tgz",
                "linux-aarch64" to "typescript-linux-arm64-7.0.2.tgz",
                "mac-aarch64" to "typescript-darwin-arm64-7.0.2.tgz",
            )
        for (platform in ToolPin.PLATFORMS) {
            val asset = checkNotNull(pin.asset(platform)) { platform }
            assertEquals(expectedNames[platform], asset.name)
            assertTrue(asset.bytes > 1_000_000, "${asset.bytes}")
            val sri = assertIs<Checksum.Sri>(asset.checksum)
            assertTrue(sri.wellFormed, sri.toString())
            assertEquals("sha512", sri.algorithm.sriName)
            val pkg = PLATFORM_PACKAGES.getValue(platform)
            assertEquals(
                URI("https://registry.npmjs.org/@typescript/typescript-$pkg/-/typescript-$pkg-7.0.2.tgz"),
                asset.url,
            )
        }
    }

    @Test
    fun `the pinned release downloads the platform package and runs tsc from package lib`() {
        val release = checkNotNull(TypeScriptTool.tool(platform = "linux-aarch64").pinnedRelease())

        assertEquals("7.0.2", release.version)
        assertEquals(8_900_516, release.bytes)
        assertEquals<Checksum?>(Checksum.sri(ARM64_INTEGRITY), release.checksum)
        assertEquals(ArchiveFormat.TAR_GZ, release.format)
        assertEquals("package/lib/tsc", release.executable)
    }

    @Test
    fun `the server runs as tsc --lsp --stdio and is checked with tsc --version`() {
        val tool = TypeScriptTool.tool(platform = "mac-aarch64")

        assertEquals(listOf("--lsp", "--stdio"), tool.serverArguments)
        assertEquals(listOf("--version"), tool.versionArguments)
        assertEquals("Version ", tool.versionPrefix)
        assertEquals("tsc", tool.commandName)
        assertEquals("TypeScript", tool.name)
    }

    @Test
    fun `the installer names itself for the install banner`() {
        assertEquals("the TypeScript language server", TypeScriptFakes(dir).installer().toolName)
    }
}
