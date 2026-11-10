package dev.sluide.python

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TyPinTest {
    @Test
    fun `the pin resource names a version and the asset of every Thoreau platform`() {
        val pin = TyPin.load()

        assertEquals("0.0.99", pin.version)
        for (platform in TY_PLATFORMS) {
            val asset = checkNotNull(pin.asset(platform)) { platform }
            assertTrue(asset.name.startsWith("ty-") && asset.name.endsWith(".tar.gz"), asset.name)
            assertTrue(asset.bytes > 1_000_000, "${asset.bytes}")
            assertTrue(Regex("[0-9a-f]{64}").matches(asset.sha256), asset.sha256)
        }
        assertEquals("ty-aarch64-apple-darwin.tar.gz", pin.asset("mac-aarch64")?.name)
        assertEquals("ty-x86_64-unknown-linux-musl.tar.gz", pin.asset("linux-x64")?.name)
    }

    @Test
    fun `the pinned release downloads the platform's asset from the GitHub release and runs ty inside its folder`() {
        val release = checkNotNull(TyPin.load().release("linux-aarch64"))

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
    fun `a platform without a build has no asset and no release`() {
        val pin = TyPin.load()

        assertNull(pin.asset(null))
        assertNull(pin.release(null))
        assertNull(pin.asset("windows-x64"))
    }

    @Test
    fun `parsing keeps the platforms that have an asset and lower-cases checksums`() {
        val properties =
            Properties().apply {
                setProperty("version", " 1.2.3 ")
                setProperty("linux-x64.asset", "ty-x86_64-unknown-linux-musl.tar.gz")
                setProperty("linux-x64.bytes", "42")
                setProperty("linux-x64.sha256", "AB".repeat(32))
            }

        val pin = TyPin.parse(properties)

        assertEquals("1.2.3", pin.version)
        assertEquals(TyAsset("ty-x86_64-unknown-linux-musl.tar.gz", 42, "ab".repeat(32)), pin.asset("linux-x64"))
        assertNull(pin.asset("mac-aarch64"))
    }

    @Test
    fun `an asset without its size is refused`() {
        val properties =
            Properties().apply {
                setProperty("version", "1.2.3")
                setProperty("mac-aarch64.asset", "ty-aarch64-apple-darwin.tar.gz")
                setProperty("mac-aarch64.sha256", "ab".repeat(32))
            }

        assertFailsWith<IllegalArgumentException> { TyPin.parse(properties) }
    }

    @ParameterizedTest
    @CsvSource(
        "Linux, amd64, linux-x64",
        "Linux, x86_64, linux-x64",
        "Linux, aarch64, linux-aarch64",
        "Mac OS X, aarch64, mac-aarch64",
        "Mac OS X, arm64, mac-aarch64",
        "Mac OS X, x86_64, ",
        "Windows 11, amd64, ",
        "Linux, riscv64, ",
        "FreeBSD, amd64, ",
    )
    fun `the platform comes from os name and arch`(
        osName: String,
        osArch: String,
        expected: String?,
    ) {
        assertEquals(expected, TyPin.platform(osName, osArch))
    }
}
