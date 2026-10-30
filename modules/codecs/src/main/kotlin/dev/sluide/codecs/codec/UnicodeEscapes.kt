package dev.sluide.codecs.codec

/** The notation [UnicodeEscapes] writes. */
enum class UnicodeForm(
    val title: String,
) {
    BACKSLASH_U("\\uXXXX"),
    BRACES("\\u{X}"),
    U_PLUS("U+X"),
}

/** Unicode escapes in the three common notations; decoding accepts all of them in one text. */
internal class UnicodeEscapes(
    private val form: UnicodeForm = UnicodeForm.BACKSLASH_U,
    private val allCharacters: Boolean = false,
) : TextCodec {
    override fun encode(text: String): Decoded<String> {
        val out = StringBuilder()
        var previousEscaped = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp <= 127 && !allCharacters) {
                out.append(cp.toChar())
                previousEscaped = false
                continue
            }
            when (form) {
                UnicodeForm.BACKSLASH_U -> {
                    for (unit in Character.toChars(cp)) out.append("\\u%04X".format(unit.code))
                }

                UnicodeForm.BRACES -> {
                    out.append("\\u{").append(cp.toString(16).uppercase()).append('}')
                }

                UnicodeForm.U_PLUS -> {
                    if (allCharacters && previousEscaped) out.append(' ')
                    out.append("U+%04X".format(cp))
                }
            }
            previousEscaped = true
        }
        return Decoded.Value(out.toString())
    }

    override fun decode(text: String): Decoded<String> {
        val out = StringBuilder()
        var i = 0
        var afterUPlus = false
        while (i < text.length) {
            val c = text[i]
            if (afterUPlus && c == ' ' && hexRun(text, i + 3, 4, 6) > 0 && text.startsWith("U+", i + 1)) {
                i++
                afterUPlus = false
                continue
            }
            afterUPlus = false
            if (c == '\\' && text.startsWith("u{", i + 1)) {
                val digits = hexRun(text, i + 3, 1, 6)
                val close = i + 3 + digits
                if (digits > 0 && text.startsWith("}", close)) {
                    val cp = text.substring(i + 3, close).toInt(16)
                    if (cp > MAX_CODE_POINT) return tooLarge(i)
                    out.appendCodePoint(cp)
                    i = close + 1
                    continue
                }
            } else if (c == '\\' && text.startsWith("u", i + 1)) {
                val unit = hex4(text, i + 2, text.length)
                if (unit != null) {
                    var length = 6
                    val low =
                        if (unit in 0xD800..0xDBFF &&
                            text.startsWith("\\u", i + 6)
                        ) {
                            hex4(text, i + 8, text.length)
                        } else {
                            null
                        }
                    out.append(unit.toChar())
                    if (low != null && low in 0xDC00..0xDFFF) {
                        out.append(low.toChar())
                        length = 12
                    }
                    i += length
                    continue
                }
            } else if (c == 'U' && text.startsWith("+", i + 1)) {
                val digits = hexRun(text, i + 2, 4, 6)
                if (digits > 0) {
                    val cp = text.substring(i + 2, i + 2 + digits).toInt(16)
                    if (cp > MAX_CODE_POINT) return tooLarge(i)
                    out.appendCodePoint(cp)
                    i += 2 + digits
                    afterUPlus = true
                    continue
                }
            }
            out.append(c)
            i++
        }
        return Decoded.Value(out.toString())
    }

    private fun tooLarge(position: Int) = Decoded.Error("Code point above U+10FFFF", position)

    /** The number of hex digits at [from] (at most [max]), or 0 when there are fewer than [min]. */
    private fun hexRun(
        text: String,
        from: Int,
        min: Int,
        max: Int,
    ): Int {
        var n = 0
        while (n < max && from + n < text.length && Character.digit(text[from + n], 16) >= 0) n++
        return if (n >= min) n else 0
    }

    private companion object {
        const val MAX_CODE_POINT = 0x10FFFF
    }
}
