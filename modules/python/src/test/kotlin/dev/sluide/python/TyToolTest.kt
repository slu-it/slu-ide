package dev.sluide.python

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.ToolPin
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ty's pin resource and how its releases are fetched and unpacked; the pin format is tested in server-tools. */
class TyToolTest {
    @Test
    fun `the pin resource names a version and the asset of every Thoreau platform`() {
        val pin = TyTool.pin()

        assertEquals("0.0.99", pin.version)
        for (platform in ToolPin.PLATFORMS) {
            val asset = checkNotNull(pin.asset(platform)) { platform }
            assertTrue(asset.name.startsWith("ty-") && asset.name.endsWith(".tar.gz"), asset.name)
            assertTrue(asset.bytes > 1_000_000, "${asset.bytes}")
            val sha256 = assertIs<Checksum.Sha256>(asset.checksum).hex
            assertTrue(Regex("[0-9a-f]{64}").matches(sha256), sha256)
        }
        assertEquals("ty-aarch64-apple-darwin.tar.gz", pin.asset("mac-aarch64")?.name)
        assertEquals("ty-x86_64-unknown-linux-musl.tar.gz", pin.asset("linux-x64")?.name)
    }

    @Test
    fun `the pinned release downloads the platform's asset from the GitHub release and runs ty inside its folder`() {
        val release = checkNotNull(TyTool.tool(platform = "linux-aarch64").pinnedRelease())

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
}
