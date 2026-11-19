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

    private fun zlib(bytes: ByteArray) =
        ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun `reads deflate as zlib when it has a zlib header and as raw data when it has none`() {
        assertEquals("zlib", String(decode("deflate", zlib("zlib".toByteArray())).bytes))
        assertEquals("raw", String(decode("deflate", rawDeflate("raw".toByteArray())).bytes))
        for (level in listOf(Deflater.BEST_SPEED, Deflater.BEST_COMPRESSION, Deflater.NO_COMPRESSION)) {
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out, Deflater(level)).use { it.write("level $level".toByteArray()) }
            assertEquals("level $level", String(decode("deflate", out.toByteArray()).bytes))
        }
    }

    @Test
    fun `reads x-gzip like gzip and ignores the case, blanks and identity around encodings`() {
        val zipped = gzip("text".toByteArray())

        assertEquals("text", String(decode("x-gzip", zipped).bytes))
        assertEquals("text", String(decode(" Identity , X-GZIP ,, IDENTITY ", zipped).bytes))
        assertNull(decode(" Identity , X-GZIP ,, IDENTITY ", zipped).note)
        assertEquals("text", String(decode(",gzip,", zipped).bytes))
        assertEquals(zipped.toList(), decode(" , IDENTITY ", zipped).bytes.toList())
        assertNull(decode(" , IDENTITY ", zipped).note)
    }

    @Test
    fun `names an encoding it does not decode and keeps what the encodings after it decoded`() {
        val zipped = gzip("text".toByteArray())

        val outer = decode("gzip, br", zipped)
        assertEquals("Content-Encoding br is not decoded", outer.note)
        assertEquals(zipped.toList(), outer.bytes.toList())

        val inner = decode("br, gzip", zipped)
        assertEquals("Content-Encoding br is not decoded", inner.note)
        assertEquals("text", String(inner.bytes))
        assertFalse(inner.truncated)
        assertFalse(inner.partial)
    }

    @Test
    fun `says that a body could not be decoded at all and keeps its bytes`() {
        val decoded = decode("gzip", byteArrayOf(1, 2, 3))

        assertEquals("The gzip body could not be decoded: Not in GZIP format", decoded.note)
        assertEquals(listOf<Byte>(1, 2, 3), decoded.bytes.toList())
        assertFalse(decoded.partial)
        assertFalse(decoded.truncated)
        assertEquals(
            "The deflate body could not be decoded",
            decode("deflate", byteArrayOf(1)).note?.substringBefore(":"),
        )
    }

    @Test
    fun `says that a body could only be decoded in part`() {
        val zipped = gzip("x".repeat(10_000).toByteArray())

        val decoded = decode("gzip", zipped.copyOf(zipped.size - 10), 100_000)

        assertEquals("The gzip body could only be decoded in part: Unexpected end of ZLIB input stream", decoded.note)
        assertEquals("x".repeat(decoded.bytes.size), String(decoded.bytes))
        assertFalse(decoded.truncated)
        assertTrue(decoded.partial)
    }

    @Test
    fun `truncates exactly beyond the limit, also across the reads of a large body`() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val zipped = gzip(body)

        val exact = decode("gzip", zipped, body.size)
        assertEquals(body.toList(), exact.bytes.toList())
        assertFalse(exact.truncated)

        val one = decode("gzip", zipped, body.size - 1)
        assertEquals(body.size - 1, one.bytes.size)
        assertTrue(one.truncated)

        val middle = decode("gzip", zipped, 70_000)
        assertEquals(body.copyOf(70_000).toList(), middle.bytes.toList())
        assertTrue(middle.truncated)

        val none = decode("gzip", zipped, 0)
        assertEquals(0, none.bytes.size)
        assertTrue(none.truncated)
    }
}
