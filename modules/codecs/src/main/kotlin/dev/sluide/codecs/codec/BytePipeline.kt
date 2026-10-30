package dev.sluide.codecs.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

enum class Compression(
    val title: String,
) {
    NONE("None"),
    GZIP("gzip"),
    ZLIB("zlib"),
    DEFLATE("Raw deflate"),
}

/** The charsets the Codecs tool offers for the text behind the bytes. */
internal val CHARSETS: List<Charset> =
    listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "ISO-8859-1", "windows-1252", "US-ASCII")
        .map { Charset.forName(it) }

/** Compression and strict charset conversion around the byte codecs. */
internal object BytePipeline {
    const val MAX_DECOMPRESSED = 16 * 1024 * 1024

    private const val GZIP_HEADER = 10
    private const val GZIP_TRAILER = 8
    private const val FHCRC = 2
    private const val FEXTRA = 4
    private const val FNAME = 8
    private const val FCOMMENT = 16

    fun compress(
        bytes: ByteArray,
        compression: Compression,
    ): ByteArray {
        if (compression == Compression.NONE) return bytes
        val out = ByteArrayOutputStream()
        if (compression == Compression.GZIP) {
            GZIPOutputStream(out).use { it.write(bytes) }
        } else {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, compression == Compression.DEFLATE)
            try {
                DeflaterOutputStream(out, deflater).use { it.write(bytes) }
            } finally {
                deflater.end()
            }
        }
        return out.toByteArray()
    }

    /**
     * Inflates with an [Inflater] directly (also for gzip, whose header and trailer are read here) so that bytes
     * after the end of the stream are an error instead of a second gzip member.
     */
    fun decompress(
        bytes: ByteArray,
        compression: Compression,
    ): Decoded<ByteArray> {
        if (compression == Compression.NONE) return Decoded.Value(bytes)
        val name = if (compression == Compression.DEFLATE) "raw deflate" else compression.title
        val notData = Decoded.Error("Not $name data")
        if (bytes.isEmpty()) return notData
        val gzip = compression == Compression.GZIP
        val start = if (gzip) gzipHeaderEnd(bytes) ?: return notData else 0
        val inflater = Inflater(compression != Compression.ZLIB)
        try {
            inflater.setInput(bytes, start, bytes.size - start)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val crc = CRC32()
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (inflater.needsDictionary()) return notData
                if (n == 0 && !inflater.finished() && inflater.needsInput()) {
                    return Decoded.Error("Compressed data is truncated")
                }
                if (out.size() + n > MAX_DECOMPRESSED) return Decoded.Error("Decompressed data is larger than 16 MiB")
                out.write(buffer, 0, n)
                crc.update(buffer, 0, n)
            }
            var rest = inflater.remaining
            if (gzip) {
                if (rest < GZIP_TRAILER) return Decoded.Error("Compressed data is truncated")
                val trailer = bytes.size - rest
                if (readIntLe(bytes, trailer) != crc.value.toInt()) return notData
                if (readIntLe(bytes, trailer + 4) != out.size()) return notData
                rest -= GZIP_TRAILER
            }
            if (rest > 0) return Decoded.Error("Data after the end of the compressed stream")
            return Decoded.Value(out.toByteArray())
        } catch (_: DataFormatException) {
            return notData
        } finally {
            inflater.end()
        }
    }

    private fun readIntLe(
        bytes: ByteArray,
        at: Int,
    ): Int = (0 until 4).sumOf { (bytes[at + it].toInt() and 0xFF) shl (8 * it) }

    /** Index after the gzip header (RFC 1952), or `null` when [bytes] do not start with one. */
    private fun gzipHeaderEnd(bytes: ByteArray): Int? {
        val magic = bytes.size >= GZIP_HEADER && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (!magic || bytes[2] != 8.toByte()) return null
        val flags = bytes[3].toInt()
        var pos = GZIP_HEADER
        if (flags and FEXTRA != 0) {
            if (pos + 2 > bytes.size) return null
            pos += 2 + (bytes[pos].toInt() and 0xFF) + ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        }
        for (flag in intArrayOf(FNAME, FCOMMENT)) {
            if (flags and flag != 0) {
                while (pos < bytes.size && bytes[pos] != 0.toByte()) pos++
                pos++
            }
        }
        if (flags and FHCRC != 0) pos += 2
        return if (pos <= bytes.size) pos else null
    }

    fun encodeText(
        text: String,
        charset: Charset,
    ): Decoded<ByteArray> {
        val encoder =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = CharBuffer.wrap(text)
        val output = ByteBuffer.allocate((text.length * encoder.maxBytesPerChar()).toInt() + 16)
        val result = encoder.encode(input, output, true)
        if (result.isError) {
            val position = input.position()
            if (result.isMalformed) return Decoded.Error("Text has an unpaired surrogate", position)
            val shown = String(Character.toChars(text.codePointAt(position)))
            return Decoded.Error("'$shown' cannot be written in ${charset.displayName()}", position)
        }
        encoder.flush(output)
        output.flip()
        return Decoded.Value(ByteArray(output.remaining()).also { output.get(it) })
    }

    /** `null` when [bytes] are not valid text in [charset]. */
    fun decodeText(
        bytes: ByteArray,
        charset: Charset,
    ): String? =
        try {
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
}
