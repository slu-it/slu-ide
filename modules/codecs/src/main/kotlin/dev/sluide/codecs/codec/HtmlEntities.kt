package dev.sluide.codecs.codec

import org.jsoup.parser.Parser

/**
 * HTML/XML character references; decoding never fails (unknown references stay as they are).
 * With [nonAsciiAsReferences] the C1 controls U+0080..U+009F stay literal: HTML decodes `&#x80;` through windows-1252
 * (to the euro sign), so a reference would not give the character back.
 */
internal class HtmlEntities(
    private val nonAsciiAsReferences: Boolean = false,
) : TextCodec {
    override fun encode(text: String): Decoded<String> {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> {
                    out.append("&amp;")
                }

                cp == '<'.code -> {
                    out.append("&lt;")
                }

                cp == '>'.code -> {
                    out.append("&gt;")
                }

                cp == '"'.code -> {
                    out.append("&quot;")
                }

                cp == '\''.code -> {
                    out.append("&#39;")
                }

                nonAsciiAsReferences && cp > ASCII_MAX && cp !in C1_CONTROLS -> {
                    out.append("&#x${cp.toString(16).uppercase()};")
                }

                else -> {
                    out.appendCodePoint(cp)
                }
            }
        }
        return Decoded.Value(out.toString())
    }

    /** All HTML5 named and numeric references, resolved by jsoup the way browsers do. */
    override fun decode(text: String): Decoded<String> = Decoded.Value(Parser.unescapeEntities(text, false))

    private companion object {
        const val ASCII_MAX = 127
        val C1_CONTROLS = 0x80..0x9F
    }
}
