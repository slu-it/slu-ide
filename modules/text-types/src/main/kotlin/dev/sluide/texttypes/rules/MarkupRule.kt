package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * XML, HTML and SVG by the prolog and the root element: the text is read from the start over the XML declaration,
 * processing instructions, comments and the doctype up to the first element's name. Nothing is parsed further.
 */
internal object MarkupRule : TextTypeSource {
    /** An `<svg` root element, or an HTML doctype. */
    const val ROOT_SCORE = 98

    /** An `<?xml` declaration, or an `<html` root element. */
    const val DECLARATION_SCORE = 95

    /** A text that starts with an element and ends with its end tag: HTML for an HTML element's name, else XML. */
    const val FRAGMENT_SCORE = 70

    private val name = Regex("[A-Za-z_][\\w:.-]*")
    private val htmlDoctype = Regex("""(?is)doctype\s+html(?:\s.*)?""")

    private val htmlElements =
        setOf(
            "a",
            "article",
            "aside",
            "body",
            "button",
            "div",
            "footer",
            "form",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "head",
            "header",
            "img",
            "input",
            "label",
            "li",
            "main",
            "nav",
            "ol",
            "option",
            "p",
            "pre",
            "section",
            "select",
            "span",
            "table",
            "tbody",
            "td",
            "textarea",
            "th",
            "thead",
            "tr",
            "ul",
        )

    override fun scores(text: String): List<TypeScore> {
        val prolog = Prolog.read(text)
        val root = prolog.root
        return when {
            root == "svg" || root?.endsWith(":svg") == true -> {
                score(TextTypes.SVG, ROOT_SCORE)
            }

            prolog.doctype?.let(htmlDoctype::matches) == true -> {
                score(TextTypes.HTML, ROOT_SCORE)
            }

            root.equals("html", ignoreCase = true) -> {
                score(TextTypes.HTML, DECLARATION_SCORE)
            }

            prolog.declaration -> {
                score(TextTypes.XML, DECLARATION_SCORE)
            }

            root != null && text.trimEnd().endsWith("</$root>") -> {
                score(if (root.lowercase() in htmlElements) TextTypes.HTML else TextTypes.XML, FRAGMENT_SCORE)
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun score(
        type: TextType,
        score: Int,
    ) = listOf(TypeScore(type, score))

    /** What precedes the first element: whether there is an XML declaration, the doctype, the root's name. */
    private class Prolog(
        val declaration: Boolean,
        val doctype: String?,
        val root: String?,
    ) {
        companion object {
            /** Reads over the prolog; a construct that is not closed runs to the end of the text. */
            fun read(text: String): Prolog {
                var at = skipWhitespace(text, 0)
                var declaration = false
                var doctype: String? = null
                while (text.startsWith("<?", at) || text.startsWith("<!", at)) {
                    when {
                        text.startsWith("<?", at) -> {
                            declaration = declaration || text.startsWith("<?xml", at)
                            at = after(text, "?>", at)
                        }

                        text.startsWith("<!--", at) -> {
                            at = after(text, "-->", at)
                        }

                        else -> {
                            val end = after(text, ">", at)
                            doctype = text.substring(at + 2, end - 1)
                            at = end
                        }
                    }
                    at = skipWhitespace(text, at)
                }
                val root = if (text.startsWith("<", at)) name.matchAt(text, at + 1)?.value else null
                return Prolog(declaration, doctype, root)
            }

            private fun skipWhitespace(
                text: String,
                from: Int,
            ): Int {
                var at = from
                while (at < text.length && text[at].isWhitespace()) at++
                return at
            }

            /** The index after the first [end] behind the two chars at [from], the text's length if there is none. */
            private fun after(
                text: String,
                end: String,
                from: Int,
            ): Int {
                val index = text.indexOf(end, from + 2)
                return if (index == -1) text.length else index + end.length
            }
        }
    }
}
