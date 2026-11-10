package dev.sluide.localmodels

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecksumTest {
    private val bytes = "thoreau".toByteArray()
    private val sha512 = MessageDigest.getInstance("SHA-512").digest(bytes)
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun `an SRI value of npm is read with its algorithm and matches its digest`() {
        val base64 = Base64.getEncoder().encodeToString(sha512)

        val checksum = assertNotNull(Checksum.sri(" sha512-$base64 "))

        assertEquals(Checksum.Sri(DigestAlgorithm.SHA512, base64), checksum)
        assertEquals("sha512-$base64", checksum.toString())
        assertTrue(checksum.wellFormed)
        assertTrue(checksum.matches(sha512))
        assertFalse(checksum.matches(sha256))
    }

    @ParameterizedTest
    @ValueSource(strings = ["md5-abc", "sha512", "sha512-", "sha512-abc sha384-def", "sha512-abc?x", "abc"])
    fun `anything but one SRI hash of a known algorithm is no SRI value`(text: String) {
        assertNull(Checksum.sri(text))
    }

    @Test
    fun `an SRI value that is no digest of its algorithm is not well-formed and matches nothing`() {
        val short = Checksum.Sri(DigestAlgorithm.SHA512, Base64.getEncoder().encodeToString(sha256))
        val broken = Checksum.Sri(DigestAlgorithm.SHA512, "not*base64")

        assertFalse(short.wellFormed)
        assertFalse(broken.wellFormed)
        assertFalse(broken.matches(sha512))
    }

    @Test
    fun `a SHA-256 is 64 hex digits and matches its digest in any case`() {
        val hex = HexFormat.of().formatHex(sha256)

        assertTrue(Checksum.Sha256(hex.uppercase()).wellFormed)
        assertTrue(Checksum.Sha256(hex.uppercase()).matches(sha256))
        assertFalse(Checksum.Sha256(hex.take(12)).wellFormed)
        assertFalse(Checksum.Sha256(hex).matches(sha512))
    }
}
