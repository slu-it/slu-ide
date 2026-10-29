package dev.sluide.coloring.detect

import dev.sluide.coloring.color.CssColorParser
import dev.sluide.coloring.color.NamedColors

/**
 * Finds color literals (hex, color functions, named colors) in one line of source text, for the gutter swatches
 * and "Open in Coloring" of code editors.
 *
 * Every line is matched alone, with no state from other lines: a block comment or a string that spans lines is not
 * tracked. Context rules keep false hits down (see [SourceKind]). Known limitations:
 * - Style files: `#123` inside a block comment, a selector on a line without `{` or `,` (`#abc`), and a
 *   pseudo-class selector (`li:hover #add {`) still match as hex. Names need a `:` on the same line, so a
 *   continuation line (`inset 0 0 red;`) has none. Quotes are not tracked (`content: "red"` matches).
 *   `//` after whitespace ends the line (SCSS and Less comments).
 * - Markup: an apostrophe in text opens a "string" for the rest of the line (`<p>don't #fff</p>` matches); names
 *   in `<style>` blocks are not matched, only hex and functions; a hex in any quoted attribute value
 *   (`href="#fed"`) matches; a colon in plain text (`<li>Fixed: #123</li>`) counts as inline CSS, so a hex after it
 *   matches; `<!--` ends the line. In `.xml` files a 3 or 6 digit hex that is a whole element's text matches.
 * - Scripts and components: a line comment or block comment start outside a string ends the line and a line starting with `*` is skipped as
 *   JSDoc, but an apostrophe in JSX text (`<p>Don't see #123</p>`) still opens a string. An attribute value written
 *   without spaces around `=` (`className="red"`) is not a plain string, so a name there matches only for color
 *   attributes.
 *
 * One left-to-right pass; only the look-backs for `url(` and `var(` can make a pathological line quadratic, bounded
 * by [MAX_LINE_LENGTH].
 */
object ColorFinder {
    const val MAX_LINE_LENGTH = 5_000
    const val MAX_RESULTS = 50

    private val FUNCTIONS = setOf("rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color")
    private val COLOR_ATTRIBUTES =
        setOf("fill", "stroke", "stop-color", "color", "bgcolor", "flood-color", "lighting-color", "background")
    private val STYLE_ATTRIBUTES = setOf("style", ":style", "v-bind:style")
    private val HEX_LENGTHS = intArrayOf(3, 4, 6, 8)
    private val ANDROID_HEX_LENGTHS = intArrayOf(3, 6)
    private const val NO_QUOTE = '\u0000'

    /** The colors in [line] in order; none for a line longer than [MAX_LINE_LENGTH]. */
    fun find(
        line: String,
        kind: SourceKind,
    ): List<FoundColor> = if (line.length > MAX_LINE_LENGTH) emptyList() else Scan(line, kind).run()

    /** The color whose range contains [column], else one that ends right before it (caret just after the text). */
    fun at(
        line: String,
        kind: SourceKind,
        column: Int,
    ): FoundColor? {
        val all = find(line, kind)
        return all.firstOrNull { column in it.range } ?: all.firstOrNull { column == it.range.last + 1 }
    }

    private class Scan(
        val s: String,
        val kind: SourceKind,
    ) {
        val n = s.length
        val found = ArrayList<FoundColor>()
        val isStyle = kind == SourceKind.STYLE
        val isScript = kind == SourceKind.SCRIPT
        val isComponent = kind == SourceKind.COMPONENT
        val isMarkup = kind == SourceKind.MARKUP || kind == SourceKind.XML
        val codeLike = isScript || isComponent
        val tracksQuotes = !isStyle
        var quote = NO_QUOTE
        var quoteStart = -1

        /** The last `:` outside a string (reset at `<` and `>` in markup), and the last one inside the current string. */
        var lastColon = -1
        var quoteColon = -1
        val trimmedEnd by lazy { s.trimEnd() }
        val endsWithBrace by lazy { trimmedEnd.endsWith("{") }
        val inQuote get() = quote != NO_QUOTE

        fun run(): List<FoundColor> {
            if (codeLike && s.trimStart().startsWith("*") && (isScript || '{' !in s)) return found
            var i = 0
            while (i < n && found.size < MAX_RESULTS) {
                val c = s[i]
                if (inQuote) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == quote) {
                        quote = NO_QUOTE
                        i++
                        continue
                    }
                    if (c == ':') quoteColon = i
                } else {
                    if (tracksQuotes && (c == '"' || c == '\'' || c == '`')) {
                        quote = c
                        quoteStart = i
                        quoteColon = -1
                        i++
                        continue
                    }
                    if (endsLine(i)) break
                    if (c == ':') lastColon = i
                    if ((isMarkup || isComponent) && (c == '<' || c == '>')) lastColon = -1
                }
                val end =
                    when {
                        c == '#' -> hex(i)
                        c.isAsciiLetter() && !(i > 0 && s[i - 1].isWordChar()) -> word(i)
                        else -> -1
                    }
                i = if (end > i) end else i + 1
            }
            return found
        }

        /** A comment starts at [i] (outside a string): the rest of the line is not searched. */
        fun endsLine(i: Int): Boolean {
            val c = s[i]
            val next = if (i + 1 < n) s[i + 1] else ' '
            return when {
                c == '/' && next == '/' && isStyle -> {
                    (i == 0 || s[i - 1].isWhitespace() || s[i - 1] == ';' || s[i - 1] == '}') &&
                        !insideFunction(i, "url")
                }

                c == '/' && next == '/' && codeLike -> {
                    (i == 0 || s[i - 1] != ':') && !insideFunction(i, "url")
                }

                c == '/' && next == '*' -> {
                    codeLike
                }

                c == '<' && (isMarkup || isComponent) -> {
                    s.startsWith("<!--", i)
                }

                else -> {
                    false
                }
            }
        }

        /** Adds the candidate if it parses; returns the index after it, or -1. */
        fun accept(
            start: Int,
            end: Int,
        ): Int {
            val text = s.substring(start, end)
            val color = CssColorParser.parse(text) ?: return -1
            found += FoundColor(start until end, text, color)
            return end
        }

        fun hex(i: Int): Int {
            if (i > 0 && (s[i - 1].isWordChar() || s[i - 1] in "\$&.#")) return -1
            var j = i + 1
            while (j < n && s[j].isHexDigit()) j++
            val lengths = if (kind == SourceKind.XML) ANDROID_HEX_LENGTHS else HEX_LENGTHS
            if (j - i - 1 !in lengths) return -1
            if (j < n && (s[j].isWordChar() || s[j] == '-')) return -1
            val markup = inQuote || lastColon >= 0
            val elementText = kind == SourceKind.XML && i > 0 && s[i - 1] == '>' && j < n && s[j] == '<'
            val ok =
                when (kind) {
                    SourceKind.SCRIPT -> inQuote
                    SourceKind.MARKUP, SourceKind.COMPONENT -> markup
                    SourceKind.XML -> markup || elementText
                    SourceKind.STYLE -> !inSelectorPosition(i, j)
                }
            return if (ok && !insideFunction(i, "url")) accept(i, j) else -1
        }

        /**
         * No `:` before it, and the line ends with `{`, or with `,` (unless it continues a multi-line value such as
         * `0 0 0 1px #000,`), or the next character continues a selector.
         */
        fun inSelectorPosition(
            start: Int,
            after: Int,
        ): Boolean {
            if (lastColon >= 0) return false
            if (endsWithBrace) return true
            if (trimmedEnd.endsWith(",")) {
                return '{' in s || !(s.trimStart().firstOrNull()?.isDigit() == true || followedBySpaceAndDigit(after))
            }
            var k = after
            while (k < n && s[k] == ' ') k++
            return k < n && s[k] in "{,.[>+~"
        }

        fun followedBySpaceAndDigit(after: Int): Boolean {
            var k = after
            while (k < n && s[k].isWhitespace()) k++
            return k > after && k < n && s[k].isDigit()
        }

        fun word(i: Int): Int {
            var j = i
            while (j < n && s[j].isAsciiLetter()) j++
            val next = if (j < n) s[j] else ' '
            if (next == '(') return function(i, j)
            if (next.isWordChar() || next in "-:=") return -1
            if (j - i !in 3..20 || (i > 0 && s[i - 1] in ".-\$@#")) return -1
            if (!nameContextOk(i, j)) return -1
            if (NamedColors.byName(s.substring(i, j)) == null) return -1
            return accept(i, j)
        }

        fun function(
            i: Int,
            nameEnd: Int,
        ): Int {
            if (i > 0 && s[i - 1] in "-\$@.#") return -1
            if (s.substring(i, nameEnd).lowercase() !in FUNCTIONS) return -1
            var k = nameEnd + 1
            while (k < n && s[k] != ')') {
                if (s[k] == '(') return -1
                k++
            }
            if (k >= n) return -1
            val ok =
                when (kind) {
                    SourceKind.SCRIPT -> inQuote
                    SourceKind.MARKUP, SourceKind.XML -> inQuote || lastColon >= 0
                    else -> true
                }
            return if (ok) accept(i, k + 1) else -1
        }

        fun nameContextOk(
            start: Int,
            end: Int,
        ): Boolean =
            when (kind) {
                SourceKind.STYLE -> {
                    styleValue(start)
                }

                SourceKind.MARKUP, SourceKind.XML -> {
                    colorAttribute(start, end) || styleAttribute(start)
                }

                SourceKind.SCRIPT -> {
                    plainString(start, end)
                }

                SourceKind.COMPONENT -> {
                    plainString(start, end) || colorAttribute(start, end) || styleAttribute(start) ||
                        styleValue(start)
                }
            }

        /** After a `:` on a line that does not end with `{`, and not inside `var(` or `url(`. */
        fun styleValue(start: Int): Boolean {
            if (lastColon < 0 || inQuote || endsWithBrace) return false
            return !insideFunction(start, "var", "url")
        }

        /** Whether [pos] is inside the unclosed parentheses of one of the functions [names]. */
        fun insideFunction(
            pos: Int,
            vararg names: String,
        ): Boolean {
            val open = s.lastIndexOf('(', pos)
            if (open < 0 || open < s.lastIndexOf(')', pos)) return false
            var k = open
            while (k > 0 && s[k - 1].isAsciiLetter()) k--
            return s.substring(k, open).lowercase() in names
        }

        fun wholeString(
            start: Int,
            end: Int,
        ): Boolean = inQuote && start == quoteStart + 1 && end < n && s[end] == quote

        /** A whole string that is not the value of a non-color attribute (`class="red"`, `name="red"`). */
        fun plainString(
            start: Int,
            end: Int,
        ): Boolean =
            wholeString(start, end) &&
                (!isAttributeValue(quoteStart) || attributeName(quoteStart) in COLOR_ATTRIBUTES)

        /** The quote at [q] directly follows `=` or `={`, as in `class="x"` and `className={'x'}`. */
        fun isAttributeValue(q: Int): Boolean =
            q > 0 && (s[q - 1] == '=' || (s[q - 1] == '{' && q >= 2 && s[q - 2] == '='))

        fun colorAttribute(
            start: Int,
            end: Int,
        ): Boolean = wholeString(start, end) && attributeName(quoteStart) in COLOR_ATTRIBUTES

        fun styleAttribute(start: Int): Boolean =
            inQuote && quoteColon in 0 until start && attributeName(quoteStart) in STYLE_ATTRIBUTES

        /** The lower-case attribute name in `name = "` before the quote at [quotePos], or an empty string. */
        fun attributeName(quotePos: Int): String {
            var k = quotePos - 1
            while (k >= 0 && s[k] == ' ') k--
            if (k >= 0 && s[k] == '{') k--
            while (k >= 0 && s[k] == ' ') k--
            if (k < 0 || s[k] != '=') return ""
            k--
            while (k >= 0 && s[k] == ' ') k--
            val end = k + 1
            while (k >= 0 && (s[k].isAsciiLetter() || s[k] == '-' || s[k] == ':')) k--
            return s.substring(k + 1, end).lowercase()
        }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isWordChar() = isLetterOrDigit() || this == '_'
}
