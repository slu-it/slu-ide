package dev.sluide.httpclient.send

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyDecodingTest {
    private fun gzip(bytes: ByteArray) =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun rawDeflate(bytes: ByteArray) =
        ByteArrayOutputStream()
            .also { out ->
                DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(bytes) }
            }.toByteArray()

    private fun decode(
        encoding: String?,
        bytes: ByteArray,
        limit: Int = 1000,
    ) = BodyDecoding.decode(encoding, bytes, limit)

    @Test
    fun `keeps bodies without or with identity encoding`() {
        assertEquals("a", String(decode(null, "a".toByteArray()).bytes))
        assertEquals("a", String(decode("identity", "a".toByteArray()).bytes))
        assertNull(decode(null, ByteArray(0)).note)
        assertNull(decode("gzip", ByteArray(0)).note)
    }

    @Test
    fun `decodes gzip, raw deflate and stacked encodings`() {
        assertEquals("text", String(decode("GZIP", gzip("text".toByteArray())).bytes))
        assertEquals("raw", String(decode("deflate", rawDeflate("raw".toByteArray())).bytes))
        assertEquals("twice", String(decode("deflate, gzip", gzip(rawDeflate("twice".toByteArray()))).bytes))
    }

    @Test
    fun `leaves unknown encodings and broken data as they are with a note`() {
        val brotli = decode("br", byteArrayOf(1, 2, 3))
        assertEquals(3, brotli.bytes.size)
        assertNotNull(brotli.note)

        val broken = decode("gzip", byteArrayOf(1, 2, 3))
        assertEquals(3, broken.bytes.size)
        assertNotNull(broken.note)
    }

    @Test
    fun `keeps what can be decoded of a truncated body`() {
        val zipped = gzip("x".repeat(10_000).toByteArray())

        val decoded = decode("gzip", zipped.copyOf(zipped.size - 10), 100_000)

        assertTrue(decoded.partial)
        assertFalse(decoded.truncated)
        assertTrue(decoded.bytes.isNotEmpty())
        assertNotNull(decoded.note)
    }

    @Test
    fun `stops decoding at the limit`() {
        val decoded = decode("gzip", gzip("x".repeat(10_000).toByteArray()), 100)

        assertEquals(100, decoded.bytes.size)
        assertTrue(decoded.truncated)
        assertFalse(decode("gzip", gzip("x".toByteArray()), 100).truncated)
    }
}
