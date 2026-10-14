package dev.sluide.httpclient.send

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Undoes the `Content-Encoding` of response bodies: `gzip` (`x-gzip`) and `deflate` (zlib or raw). */
internal object BodyDecoding {
    /**
     * The decoded body: [truncated] if decoding stopped at the limit, [partial] if the data ended or broke before
     * the end of the encoding (e.g. because the encoded body was truncated); [note] says what went wrong.
     */
    class Decoded(
        val bytes: ByteArray,
        val truncated: Boolean,
        val partial: Boolean,
        val note: String?,
    )

    /** [bytes] decoded per [contentEncoding] (encodings applied in order), at most [limit] bytes of output. */
    fun decode(
        contentEncoding: String?,
        bytes: ByteArray,
        limit: Int,
    ): Decoded {
        val encodings =
            contentEncoding
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() && it != "identity" }
                .orEmpty()
        if (bytes.isEmpty()) return Decoded(bytes, truncated = false, partial = false, note = null)
        var current = bytes
        var truncated = false
        for (encoding in encodings.asReversed()) {
            if (encoding !in setOf("gzip", "x-gzip", "deflate")) {
                return Decoded(
                    current,
                    truncated = false,
                    partial = false,
                    note = "Content-Encoding $encoding is not decoded",
                )
            }
            val out = ByteArrayOutputStream()
            val inflater = if (encoding == "deflate") Inflater(!isZlib(current)) else null
            try {
                val input = ByteArrayInputStream(current)
                val stream = if (inflater == null) GZIPInputStream(input) else InflaterInputStream(input, inflater)
                stream.use { truncated = copy(it, out, limit) }
            } catch (e: IOException) {
                val decoded = out.size() > 0
                val note = "The $encoding body could ${if (decoded) "only be decoded in part" else "not be decoded"}"
                return Decoded(if (decoded) out.toByteArray() else current, false, decoded, "$note: ${e.message}")
            } finally {
                inflater?.end()
            }
            current = out.toByteArray()
        }
        return Decoded(current, truncated, partial = false, note = null)
    }

    /** Copies up to [limit] bytes; true if there was more. */
    private fun copy(
        input: InputStream,
        out: ByteArrayOutputStream,
        limit: Int,
    ): Boolean {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return false
            val room = limit - out.size()
            out.write(buffer, 0, minOf(read, room))
            if (read > room) return true
        }
    }

    /** Whether [bytes] start with a zlib header (deflate as the HTTP spec means it) rather than raw deflate data. */
    private fun isZlib(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first and 0x0f == 8 && (first * 256 + second) % 31 == 0
    }
}
