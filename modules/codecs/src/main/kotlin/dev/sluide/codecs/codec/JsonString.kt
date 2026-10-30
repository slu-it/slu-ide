package dev.sluide.codecs.codec

/**
 * The content of a JSON string literal (RFC 8259); quotes around it are optional when decoding.
 * Decoding is deliberately lenient: raw control characters and unescaped quotes inside are kept, because users
 * paste fragments.
 */
internal class JsonString(
    private val nonAsciiAsEscapes: Boolean = false,
) : TextCodec {
    override fun encode(text: String): Decoded<String> {
        val out = StringBuilder()
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || (nonAsciiAsEscapes && c.code > 127) -> out.append("\\u%04X".format(c.code))
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
                '"', '\\', '/' -> {
                    out.append(e)
                }

                'b' -> {
                    out.append('\b')
                }

                'f' -> {
                    out.append('\u000C')
                }

                'n' -> {
                    out.append('\n')
                }

                'r' -> {
                    out.append('\r')
                }

                't' -> {
                    out.append('\t')
                }

                'u' -> {
                    val unit = hex4(text, i + 2, end) ?: return Decoded.Error("Invalid unicode escape", i)
                    out.append(unit.toChar())
                    i += 4
                }

                else -> {
                    return Decoded.Error("Invalid escape '\\$e'", i)
                }
            }
            i += 2
        }
        return Decoded.Value(out.toString())
    }
}
