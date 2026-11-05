package dev.sluide.translation.text

/** HTML syntax shared by the block and the inline scanner. */
internal object HtmlTags {
    /** The end marker of an HTML block that ends at a blank line. */
    const val UNTIL_BLANK = ""

    private const val MAX_TAG = 1000
    private const val NO_QUOTE = '\u0000'
    private val RAW = setOf("script", "pre", "style", "textarea")
    private val BLOCK =
        setOf(
            "address",
            "article",
            "aside",
            "base",
            "basefont",
            "blockquote",
            "body",
            "caption",
            "center",
            "col",
            "colgroup",
            "dd",
            "details",
            "dialog",
            "dir",
            "div",
            "dl",
            "dt",
            "fieldset",
            "figcaption",
            "figure",
            "footer",
            "form",
            "frame",
            "frameset",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "head",
            "header",
            "hr",
            "html",
            "iframe",
            "legend",
            "li",
            "link",
            "main",
            "menu",
            "menuitem",
            "nav",
            "noframes",
            "ol",
            "optgroup",
            "option",
            "p",
            "param",
            "search",
            "section",
            "summary",
            "table",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "title",
            "tr",
            "track",
            "ul",
        )

    /**
     * The end marker of an HTML block starting at [q] (a `<`) in the line to [end], [UNTIL_BLANK], or null when the
     * line starts none. A lone complete tag starts a block only outside a paragraph ([paragraphOpen]).
     */
    fun blockStart(
        text: String,
        q: Int,
        end: Int,
        paragraphOpen: Boolean,
    ): String? {
        when {
            text.startsWith("<!--", q) -> return "-->"
            text.startsWith("<?", q) -> return "?>"
            text.startsWith("<![CDATA[", q) -> return "]]>"
            text.startsWith("<!", q) && q + 2 < end && isLetter(text[q + 2]) -> return ">"
        }
        var j = q + 1
        val closing = j < end && text[j] == '/'
        if (closing) j++
        val nameStart = j
        while (j < end && (isLetter(text[j]) || text[j].isDigit())) j++
        val name = text.substring(nameStart, j).lowercase()
        val after = if (j < end) text[j] else ' '
        val ends =
            j >= end || after == ' ' || after == '\t' || after == '>' || (after == '/' && text.getOrNull(j + 1) == '>')
        if (name.isNotEmpty() && ends) {
            if (!closing && name in RAW) return "</$name>"
            if (name in BLOCK) return UNTIL_BLANK
        }
        // A name followed by `:` or `@` is an autolink, which is inline; a tag name is followed by a blank, `/` or `>`.
        if (!paragraphOpen && ends) {
            val tagEnd = tagEnd(text, q, end)
            if (tagEnd > 0 && BlockSyntax.firstNonWs(text, tagEnd, end) >= end) return UNTIL_BLANK
        }
        return null
    }

    /**
     * The exclusive end of the HTML tag (open or closing) or autolink that starts at the `<` at [i], within the line
     * to [end]; -1 when there is none. Comments are not tags. Attribute values in quotes may hold `>`.
     */
    fun tagEnd(
        text: String,
        i: Int,
        end: Int,
    ): Int {
        var j = i + 1
        if (j < end && text[j] == '/') j++
        if (j >= end || !isLetter(text[j])) return -1
        while (j < end && (isLetter(text[j]) || text[j].isDigit() || text[j] == '-')) j++
        if (j >= end) return -1
        val next = text[j]
        if (!(next.isWhitespace() || next in "/>:@.+")) return -1
        val limit = minOf(end, i + MAX_TAG)
        var quote = NO_QUOTE
        while (j < limit) {
            val c = text[j]
            when {
                quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                c == '"' || c == '\'' -> if (text[j - 1].isWhitespace() || text[j - 1] == '=') quote = c
                c == '<' -> return -1
                c == '>' -> return j + 1
            }
            j++
        }
        return -1
    }

    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
}
