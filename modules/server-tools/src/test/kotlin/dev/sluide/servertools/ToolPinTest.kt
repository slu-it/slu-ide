package dev.sluide.servertools

import dev.sluide.localmodels.Checksum
import dev.sluide.localmodels.DigestAlgorithm
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ToolPinTest {
    private val url = { version: String, platform: String, asset: String ->
        URI("https://example.com/$platform/$version/$asset")
    }

    private fun properties(vararg entries: Pair<String, String>) =
        Properties().apply { entries.forEach { (key, value) -> setProperty(key, value) } }

    @Test
    fun `parsing keeps the platforms that have an asset, lower-cases SHA-256 values and asks the tool for the URL`() {
        val pin =
            ToolPin.parse(
                properties(
                    "version" to " 1.2.3 ",
                    "linux-x64.asset" to "acme-x64.tar.gz",
                    "linux-x64.bytes" to "42",
                    "linux-x64.sha256" to "AB".repeat(32),
                ),
                url,
            )

        assertEquals("1.2.3", pin.version)
        assertEquals(
            ToolAsset(
                "acme-x64.tar.gz",
                URI("https://example.com/linux-x64/1.2.3/acme-x64.tar.gz"),
                42,
                Checksum.Sha256("ab".repeat(32)),
            ),
            pin.asset("linux-x64"),
        )
        assertNull(pin.asset("mac-aarch64"))
        assertNull(pin.asset(null))
    }

    @Test
    fun `an asset may be pinned by an SRI integrity instead`() {
        val base64 = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        val pin =
            ToolPin.parse(
                properties(
                    "version" to "7.0.2",
                    "mac-aarch64.asset" to "acme-darwin-arm64-7.0.2.tgz",
                    "mac-aarch64.bytes" to "9000000",
                    "mac-aarch64.integrity" to "sha512-$base64",
                ),
                url,
            )

        assertEquals(Checksum.Sri(DigestAlgorithm.SHA512, base64), pin.asset("mac-aarch64")?.checksum)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "sha256=abab|pin without the size of acme.tar.gz",
            "bytes=42|pin without the checksum of acme.tar.gz",
            "bytes=42;integrity=md5-abc|pin with an unreadable integrity of acme.tar.gz",
            "bytes=42;integrity=sha512-YWJj|pin with a checksum of acme.tar.gz that is not a SHA-512 value",
            "bytes=42;sha256=abab|pin with a checksum of acme.tar.gz that is not a SHA-256 value",
            "bytes=42;sha256=abab;integrity=sha512-YWJj|pin with both a sha256 and an integrity of acme.tar.gz",
        ],
    )
    fun `an asset without its size or a single well-formed checksum is refused`(
        entries: String,
        message: String,
    ) {
        val properties = properties("version" to "1.2.3", "mac-aarch64.asset" to "acme.tar.gz")
        for (entry in entries.split(';')) {
            val (key, value) = entry.split('=', limit = 2)
            properties.setProperty("mac-aarch64.$key", value)
        }

        val e = assertFailsWith<IllegalArgumentException> { ToolPin.parse(properties, url) }
        assertEquals(message, e.message)
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
        assertEquals(expected, ToolPin.platform(osName, osArch))
    }
}
