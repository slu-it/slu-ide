package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.Charset

class BytePipelineTest {
    @ParameterizedTest
    @EnumSource(Compression::class)
    fun `compression round trip`(compression: Compression) {
        val bytes = "hello hello hello hello".toByteArray()
        val packed = BytePipeline.compress(bytes, compression)
        assertEquals(bytes.toList(), (BytePipeline.decompress(packed, compression) as Decoded.Value).value.toList())
    }

    @Test
    fun `non-compressed bytes are an error`() {
        val junk = "plain text, not packed".toByteArray()
        assertEquals(Decoded.Error("Not gzip data"), BytePipeline.decompress(junk, Compression.GZIP))
        assertEquals(Decoded.Error("Not zlib data"), BytePipeline.decompress(junk, Compression.ZLIB))
        assertEquals(Decoded.Error("Not raw deflate data"), BytePipeline.decompress(junk, Compression.DEFLATE))
    }

    @ParameterizedTest
    @EnumSource(Compression::class, mode = EnumSource.Mode.EXCLUDE, names = ["NONE"])
    fun `truncated input is an error`(compression: Compression) {
        val packed = BytePipeline.compress(ByteArray(5000) { (it % 251).toByte() }, compression)
        val cut = packed.copyOf(packed.size / 2)
        assertEquals(Decoded.Error("Compressed data is truncated"), BytePipeline.decompress(cut, compression))
    }

    @ParameterizedTest
    @EnumSource(Compression::class, mode = EnumSource.Mode.EXCLUDE, names = ["NONE"])
    fun `bytes after the end of the stream are an error`(compression: Compression) {
        val packed = BytePipeline.compress("hi".toByteArray(), compression) + "JUNK".toByteArray()
        assertEquals(
            Decoded.Error("Data after the end of the compressed stream"),
            BytePipeline.decompress(packed, compression),
        )
    }

    @ParameterizedTest
    @EnumSource(Compression::class, mode = EnumSource.Mode.EXCLUDE, names = ["NONE"])
    fun `empty input is not compressed data`(compression: Compression) {
        val name = if (compression == Compression.DEFLATE) "raw deflate" else compression.title
        assertEquals(Decoded.Error("Not $name data"), BytePipeline.decompress(ByteArray(0), compression))
    }

    private fun gzipWithHeaderFields(
        flags: Int,
        fields: ByteArray,
    ): ByteArray {
        val packed = BytePipeline.compress("hi".toByteArray(), Compression.GZIP)
        packed[3] = flags.toByte()
        return packed.copyOfRange(0, 10) + fields + packed.copyOfRange(10, packed.size)
    }

    @ParameterizedTest
    @MethodSource("gzipHeaderFlags")
    fun `gzip header fields are skipped`(
        flags: Int,
        fields: ByteArray,
    ) {
        val result = BytePipeline.decompress(gzipWithHeaderFields(flags, fields), Compression.GZIP)
        assertEquals("hi", String((result as Decoded.Value).value))
    }

    @ParameterizedTest
    @MethodSource("shortGzipHeaders")
    fun `a gzip header that is cut short is not gzip data`(header: ByteArray) {
        assertEquals(Decoded.Error("Not gzip data"), BytePipeline.decompress(header, Compression.GZIP))
    }

    @Test
    fun `a wrong gzip CRC32 is refused`() {
        val packed = BytePipeline.compress("hi".toByteArray(), Compression.GZIP)
        packed[packed.size - 8] = (packed[packed.size - 8].toInt() xor 1).toByte()
        assertEquals(Decoded.Error("Not gzip data"), BytePipeline.decompress(packed, Compression.GZIP))
    }

    @Test
    fun `a wrong gzip ISIZE is refused`() {
        val packed = BytePipeline.compress("hi".toByteArray(), Compression.GZIP)
        packed[packed.size - 1] = (packed[packed.size - 1].toInt() xor 1).toByte()
        assertEquals(Decoded.Error("Not gzip data"), BytePipeline.decompress(packed, Compression.GZIP))
    }

    @Test
    // A separate thread, so that a missing check fails the test instead of spinning in its loop forever.
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a zlib stream that needs a dictionary is refused`() {
        val withDictionary = byteArrayOf(0x78, 0x20, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        assertEquals(Decoded.Error("Not zlib data"), BytePipeline.decompress(withDictionary, Compression.ZLIB))
    }

    @Test
    fun `encodeText reports an unpaired surrogate`() {
        assertEquals(
            Decoded.Error("Text has an unpaired surrogate", 1),
            BytePipeline.encodeText("a\uD800b", Charsets.UTF_8),
        )
    }

    @ParameterizedTest
    @MethodSource("decodeTextCases")
    fun `decodeText table`(
        bytes: ByteArray,
        charset: Charset,
        expected: String?,
    ) {
        assertEquals(expected, BytePipeline.decodeText(bytes, charset))
    }

    companion object {
        private val name = "a.txt\u0000".toByteArray()
        private val comment = "note\u0000".toByteArray()

        @JvmStatic
        fun gzipHeaderFlags() =
            listOf(
                Arguments.of(0x08, name),
                Arguments.of(0x1E, byteArrayOf(2, 0, 0x11, 0x22) + name + comment + byteArrayOf(0x33, 0x44)),
            )

        @JvmStatic
        fun shortGzipHeaders() =
            listOf(
                Arguments.of(b(0x1f, 0x8b, 0x08, 0x08, 0, 0, 0, 0, 0, 0x03, 0x61, 0x62)),
                Arguments.of(b(0x1f, 0x8b, 0x08, 0x04, 0, 0, 0, 0, 0, 0, 0xff, 0xff)),
            )

        private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        @JvmStatic
        fun decodeTextCases() =
            listOf(
                Arguments.of(b(0xD8, 0x00), Charsets.UTF_16BE, null),
                Arguments.of(b(0x41, 0x00, 0x42), Charsets.UTF_16LE, null),
                Arguments.of(b(0xFE, 0xFF, 0x00, 0x41), Charsets.UTF_16, "A"),
                Arguments.of(b(0x81), Charset.forName("windows-1252"), null),
                Arguments.of(b(0x63, 0x61, 0x66, 0xE9), Charsets.ISO_8859_1, "caf\u00E9"),
            )
    }

    @Test
    fun `output over 16 MiB is refused`() {
        val packed = BytePipeline.compress(ByteArray(17 * 1024 * 1024), Compression.GZIP)
        assertEquals(
            Decoded.Error("Decompressed data is larger than 16 MiB"),
            BytePipeline.decompress(packed, Compression.GZIP),
        )
    }

    @Test
    fun `encodeText reports the first unmappable character`() {
        val ascii = Charsets.US_ASCII
        assertEquals(Decoded.Error("'ü' cannot be written in US-ASCII", 2), BytePipeline.encodeText("abü", ascii))
        assertEquals("ab", String((BytePipeline.encodeText("ab", ascii) as Decoded.Value).value))
        assertEquals(
            listOf<Byte>(0xFC.toByte()),
            (BytePipeline.encodeText("ü", Charsets.ISO_8859_1) as Decoded.Value).value.toList(),
        )
    }

    @Test
    fun `decodeText is strict`() {
        assertNull(BytePipeline.decodeText(byteArrayOf(0xFC.toByte()), Charsets.UTF_8))
        assertEquals("ü", BytePipeline.decodeText(byteArrayOf(0xFC.toByte()), Charsets.ISO_8859_1))
        assertEquals("ü", BytePipeline.decodeText("ü".toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun `CHARSETS lists seven charsets in order`() {
        assertEquals(
            listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "ISO-8859-1", "windows-1252", "US-ASCII"),
            CHARSETS.map { it.name() },
        )
    }
}
