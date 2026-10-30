package dev.sluide.codecs.codec

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Base64 (RFC 4648), standard or URL-safe alphabet. Decoding is lenient; see the options for encoding. */
internal class Base64Codec(
    private val urlSafe: Boolean = false,
    private val padding: Boolean = true,
    private val wrap76: Boolean = false,
    private val dataUriMime: String? = null,
) : ByteCodec {
    override fun encode(bytes: ByteArray): Decoded<String> {
        var encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
        if (!padding) encoder = encoder.withoutPadding()
        val payload = encoder.encodeToString(bytes)
        val body = if (wrap76) payload.chunked(LINE_LENGTH).joinToString("\n") else payload
        return Decoded.Value(if (dataUriMime != null) "data:$dataUriMime;base64,$body" else body)
    }

    override fun decode(text: String): Decoded<ByteArray> {
        val lead = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val start = DataUri.parse(text.substring(lead))?.takeIf { it.base64 }?.let { lead + it.payloadStart } ?: 0
        val values = ArrayList<Int>()
        var lastIndex = -1
        var paddingSeen = false
        for (i in start until text.length) {
            val c = text[i]
            if (c.isWhitespace()) continue
            if (c == '=') {
                paddingSeen = true
                continue
            }
            if (paddingSeen) return Decoded.Error("Base64 has data after '='", i)
            val v = valueOf(c) ?: return Decoded.Error("Invalid Base64 character '$c'", i)
            values.add(v)
            lastIndex = i
        }
        if (values.size % 4 == 1) return Decoded.Error("Base64 ends in the middle of a byte", lastIndex)
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (v in values) {
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return Decoded.Value(out.toByteArray())
    }

    private fun valueOf(c: Char): Int? =
        when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '+', '-' -> 62
            '/', '_' -> 63
            else -> null
        }

    private companion object {
        const val LINE_LENGTH = 76
    }
}
