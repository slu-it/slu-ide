package dev.sluide.codecs.codec

import java.io.ByteArrayOutputStream

/** Base32 (RFC 4648) with the standard or the extended hex alphabet. */
internal class Base32Codec(
    hexAlphabet: Boolean = false,
    private val padding: Boolean = true,
) : ByteCodec {
    private val alphabet = if (hexAlphabet) HEX_ALPHABET else STANDARD_ALPHABET

    override fun encode(bytes: ByteArray): Decoded<String> {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(alphabet[(buffer shr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 31])
        if (padding) while (sb.length % 8 != 0) sb.append('=')
        return Decoded.Value(sb.toString())
    }

    override fun decode(text: String): Decoded<ByteArray> {
        val values = ArrayList<Int>()
        var lastIndex = -1
        var paddingSeen = false
        for ((i, c) in text.withIndex()) {
            if (c.isWhitespace()) continue
            if (c == '=') {
                paddingSeen = true
                continue
            }
            if (paddingSeen) return Decoded.Error("Base32 has data after '='", i)
            val v = alphabet.indexOf(if (c in 'a'..'z') c.uppercaseChar() else c)
            if (v < 0) return Decoded.Error("Invalid Base32 character '$c'", i)
            values.add(v)
            lastIndex = i
        }
        if (values.size % 8 in INVALID_REMAINDERS) {
            return Decoded.Error("Base32 ends in the middle of a byte", lastIndex)
        }
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (v in values) {
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return Decoded.Value(out.toByteArray())
    }

    private companion object {
        const val STANDARD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        const val HEX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUV"
        val INVALID_REMAINDERS = setOf(1, 3, 6)
    }
}
