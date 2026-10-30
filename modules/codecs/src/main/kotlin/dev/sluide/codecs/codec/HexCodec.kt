package dev.sluide.codecs.codec

import java.io.ByteArrayOutputStream
import java.util.HexFormat

enum class HexSeparator(
    val title: String,
    val text: String,
) {
    NONE("None", ""),
    SPACE("Space", " "),
    COLON("Colon", ":"),
}

/** Hex text. Decoding ignores whitespace, `:`, `-`, `,` and `0x` prefixes. */
internal class HexCodec(
    private val upperCase: Boolean = false,
    private val separator: HexSeparator = HexSeparator.NONE,
) : ByteCodec {
    override fun encode(bytes: ByteArray): Decoded<String> {
        var format = HexFormat.ofDelimiter(separator.text)
        if (upperCase) format = format.withUpperCase()
        return Decoded.Value(format.formatHex(bytes))
    }

    override fun decode(text: String): Decoded<ByteArray> {
        val out = ByteArrayOutputStream()
        var high = -1
        var lastIndex = -1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val prefix = c == '0' && i + 1 < text.length && (text[i + 1] == 'x' || text[i + 1] == 'X')
            if (c.isWhitespace() || c == ':' || c == '-' || c == ',' || prefix) {
                if (high >= 0) return Decoded.Error("Hex ends in the middle of a byte", i)
                i += if (prefix) 2 else 1
            } else {
                val v = if (c.code < ASCII_LIMIT) Character.digit(c, 16) else -1
                if (v < 0) return Decoded.Error("Invalid hex character '$c'", i)
                if (high < 0) {
                    high = v
                } else {
                    out.write((high shl 4) or v)
                    high = -1
                }
                lastIndex = i
                i++
            }
        }
        if (high >= 0) return Decoded.Error("Hex ends in the middle of a byte", lastIndex)
        return Decoded.Value(out.toByteArray())
    }

    private companion object {
        const val ASCII_LIMIT = 128
    }
}
