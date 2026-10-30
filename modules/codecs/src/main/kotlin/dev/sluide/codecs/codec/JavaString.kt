package dev.sluide.codecs.codec

/**
 * The content of a Java or Kotlin string literal; quotes around it are optional when decoding.
 * Java's `\u` pre-lexing (`\u005Cn` read as `\n`) is not applied. Kotlin has no `\f`, so [kotlinDollar] writes
 * form feed as `\u000C`.
 */
internal class JavaString(
    private val nonAsciiAsEscapes: Boolean = false,
    private val kotlinDollar: Boolean = false,
) : TextCodec {
    override fun encode(text: String): Decoded<String> {
        val out = StringBuilder()
        for (c in text) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '"' -> out.append("\\\"")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\u000C' && !kotlinDollar -> out.append("\\f")
                c == '$' && kotlinDollar -> out.append("\\$")
                c < ' ' || c.code == 127 || (nonAsciiAsEscapes && c.code > 127) -> out.append("\\u%04X".format(c.code))
                else -> out.append(c)
            }
        }
        return Decoded.Value(out.toString())
    }

    override fun decode(text: String): Decoded<String> {
        val quoted = text.length >= 2 && text.first() == '"' && text.last() == '"'
        val start = if (quoted) 1 else 0
        val end = if (quoted) text.length - 1 else text.length
        val out = StringBuilder()
        var i = start
        while (i < end) {
            val c = text[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            val e = if (i + 1 < end) text[i + 1] else return Decoded.Error("Incomplete escape at end of text", i)
            when (e) {
                't' -> {
                    out.append('\t')
                    i += 2
                }

                'b' -> {
                    out.append('\b')
                    i += 2
                }

                'n' -> {
                    out.append('\n')
                    i += 2
                }

                'r' -> {
                    out.append('\r')
                    i += 2
                }

                'f' -> {
                    out.append('\u000C')
                    i += 2
                }

                's' -> {
                    out.append(' ')
                    i += 2
                }

                '\'', '"', '\\', '$' -> {
                    out.append(e)
                    i += 2
                }

                'u' -> {
                    var digits = i + 1
                    while (digits < end && text[digits] == 'u') digits++
                    val unit = hex4(text, digits, end) ?: return Decoded.Error("Invalid unicode escape", i)
                    out.append(unit.toChar())
                    i = digits + 4
                }

                in '0'..'7' -> {
                    val maxLength = if (e <= '3') 3 else 2
                    var j = i + 1
                    var value = 0
                    while (j < end && j - (i + 1) < maxLength && text[j] in '0'..'7') {
                        value = value * 8 + (text[j] - '0')
                        j++
                    }
                    out.append(value.toChar())
                    i = j
                }

                else -> {
                    return Decoded.Error("Invalid escape '\\$e'", i)
                }
            }
        }
        return Decoded.Value(out.toString())
    }
}
