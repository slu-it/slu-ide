package dev.sluide.codecs.codec

/** The notation [UnicodeEscapes] writes. */
enum class UnicodeForm(
    val title: String,
) {
    BACKSLASH_U("\\uXXXX"),
    BRACES("\\u{X}"),
    U_PLUS("U+X"),
}

/**
 * Unicode escapes in the three common notations; decoding accepts all of them in one text.
 *
 * `U+` escapes have no end marker, so in that form one space is written after an escape when the next character is a
 * hex digit or a space (or any character with [allCharacters]); decoding drops one space after every `U+` escape.
 */
internal class UnicodeEscapes(
    private val form: UnicodeForm = UnicodeForm.BACKSLASH_U,
    private val allCharacters: Boolean = false,
) : TextCodec {
    override fun encode(text: String): Decoded<String> {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp <= 127 && !allCharacters) {
                out.append(cp.toChar())
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
                    out.append("U+%04X".format(cp))
                    if (i < text.length && (allCharacters || text[i] == ' ' || hexValue(text[i]) >= 0)) {
                        out.append(' ')
                    }
                }
            }
        }
        return Decoded.Value(out.toString())
    }

    override fun decode(text: String): Decoded<String> {
        val out = StringBuilder()
        var i = 0
        var afterUPlus = false
        while (i < text.length) {
            val c = text[i]
            if (afterUPlus && c == ' ') {
                i++
                afterUPlus = false
                continue
            }
            afterUPlus = false
            if (c == '\\' && text.startsWith("u{", i + 1)) {
                val digits = hexRun(text, i + 3, 1, 6)
                val close = i + 3 + digits
                if (digits > 0 && text.startsWith("}", close)) {
                    val cp = checkNotNull(hexNumber(text, i + 3, close)) { "hexRun counted only hex digits" }
                    if (cp > MAX_CODE_POINT) return tooLarge(i)
                    out.appendCodePoint(cp)
                    i = close + 1
                    continue
                }
            } else if (c == '\\' && text.startsWith("u", i + 1)) {
                val unit = hex4(text, i + 2, text.length)
                if (unit != null) {
                    out.append(unit.toChar())
                    i += 6
                    continue
                }
            } else if (c == 'U' && text.startsWith("+", i + 1)) {
                val digits = hexRun(text, i + 2, 4, 6)
                if (digits > 0) {
                    val cp = checkNotNull(hexNumber(text, i + 2, i + 2 + digits)) { "hexRun counted only hex digits" }
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
        while (n < max && from + n < text.length && hexValue(text[from + n]) >= 0) n++
        return if (n >= min) n else 0
    }

    private companion object {
        const val MAX_CODE_POINT = 0x10FFFF
    }
}
