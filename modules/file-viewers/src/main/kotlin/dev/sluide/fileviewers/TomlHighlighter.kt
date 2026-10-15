package dev.sluide.fileviewers

/**
 * Highlights TOML 1.0 (`pyproject.toml`, `uv.lock`, `Cargo.lock`), which no RSyntaxTextArea lexer knows: comments,
 * table headers (`[a.b]`, `[[c]]`, the whole header) as `type`, keys (bare, quoted and dotted, at the start of a line
 * or inside inline tables) as `property`, all four kinds of strings, numbers with dates and times, and `true`/`false`.
 *
 * One pass over the text with a stack of the open inline tables and arrays, so that a key is told from a value and
 * a header from an array. Nothing is flagged as an error: an unterminated string runs to the end of its line, a
 * multi-line one to the end of the text, and stray closing brackets are skipped.
 *
 * Unclosed brackets (`deps = [` while typing) do not swallow the rest of the file. A closing bracket that does not
 * match the innermost one closes everything down to its opener, if one is open. And at the start of a line, what an
 * array cannot contain ends all open brackets:
 * - a key followed by `=` (`name = "x"`), unless the innermost bracket is an inline table, where it is the next key
 *   (inline tables may span lines in TOML 1.1);
 * - a line that is only a table header (`[a.b]`, `[[c]]`, then at most a comment) whose key path cannot be a value: not
 *   a single quoted string, not bare words starting with a digit or `-` (numbers, dates like `[1.5]`), not `true`,
 *   `false`, `inf` or `nan`. So `[1]` or `["a"]` alone on a line stay nested arrays.
 */
internal object TomlHighlighter : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> = Scan(text).run()

    private class Scan(
        val text: String,
    ) {
        val spans = mutableListOf<HighlightSpan>()

        /** The open inline tables (`true`) and arrays (`false`), innermost last. */
        val open = ArrayDeque<Boolean>()

        /** How many inline tables are [open]; the others are arrays. */
        var tables = 0

        /** Whether a key may come next: at the start of a line outside brackets, after `{` or a `,` in a table. */
        var keyNext = true

        /** Whether only blanks came before [i] on its line. */
        var lineStart = true

        /** Whether the last [string] scanned was closed by its quote(s). */
        var closed = false
        var i = 0

        fun run(): List<HighlightSpan> {
            while (i < text.length) {
                val c = text[i]
                if (c == '\n') {
                    if (open.isEmpty()) keyNext = true
                    lineStart = true
                    i++
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    i++
                } else {
                    if (lineStart && open.isNotEmpty()) recover(c)
                    lineStart = false
                    token(c)
                }
            }
            return spans
        }

        fun token(c: Char) {
            when {
                c == '#' -> {
                    comment()
                }

                c == '[' && keyNext && open.isEmpty() -> {
                    header()
                }

                keyNext && isKeyStart(i) -> {
                    val end = keyEnd(i)
                    add(i, end, TokenStyle.PROPERTY)
                    i = end
                    keyNext = false
                }

                c == '"' || c == '\'' -> {
                    val end = string(i)
                    add(i, end, TokenStyle.STRING)
                    i = end
                    keyNext = false
                }

                else -> {
                    punctuationOrValue(c)
                }
            }
        }

        /** At the start of a line inside brackets: ends them if the line cannot belong there (see the class). */
        fun recover(c: Char) {
            if (c == '[') {
                if (isWholeHeader(i)) closeAll()
            } else if (isKeyStart(i) && isAssignment(i)) {
                if (open.last()) keyNext = true else closeAll()
            }
        }

        fun closeAll() {
            open.clear()
            tables = 0
            keyNext = true
        }

        fun add(
            start: Int,
            end: Int,
            style: TokenStyle,
        ) {
            if (end > start) spans += HighlightSpan(start, end, style)
        }

        fun comment() {
            val start = i
            while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
            add(start, i, TokenStyle.COMMENT)
        }

        /** `[table]` or `[[array.of.tables]]`, up to its closing bracket, a comment or the end of the line. */
        fun header() {
            val start = i
            val double = text.startsWith("[[", i)
            i += if (double) 2 else 1
            var end = -1
            while (i < text.length && end < 0) {
                when (text[i]) {
                    '"', '\'' -> {
                        i = string(i, singleLine = true)
                    }

                    ']' -> {
                        i += if (double && text.startsWith("]]", i)) 2 else 1
                        end = i
                    }

                    '#', '\n' -> {
                        end = i
                    }

                    else -> {
                        i++
                    }
                }
            }
            if (end < 0) end = i
            while (end > start && text[end - 1].isWhitespace()) end--
            add(start, end, TokenStyle.TYPE)
            keyNext = false
        }

        /** Whether a key starts at [at]: a bare character or a quote that does not open a multi-line string. */
        fun isKeyStart(at: Int): Boolean {
            val c = text[at]
            return isBare(c) || ((c == '"' || c == '\'') && !isTriple(at))
        }

        /**
         * The end of the bare, quoted or dotted key (`a`, `"a b"`, `a . "b.c".d`) starting at [from]: of its last part,
         * so that it is one span.
         */
        fun keyEnd(from: Int): Int {
            var at = from
            while (true) {
                val end = if (isBare(text[at])) bareEnd(at) else string(at, singleLine = true)
                var next = blanksEnd(end)
                if (next >= text.length || text[next] != '.') return end
                next = blanksEnd(next + 1)
                if (next >= text.length || !isKeyStart(next)) return end
                at = next
            }
        }

        /** Whether the key starting at [at] is followed by `=`. */
        fun isAssignment(at: Int): Boolean {
            val next = blanksEnd(keyEnd(at))
            return next < text.length && text[next] == '='
        }

        /**
         * Whether the line from [at] (a `[`) is only a table header whose key path cannot be a value, followed by at
         * most blanks and a comment.
         */
        fun isWholeHeader(at: Int): Boolean {
            val double = text.startsWith("[[", at)
            val first = blanksEnd(at + if (double) 2 else 1)
            var next = first
            var parts = 0
            var allBare = true
            var end: Int
            while (true) {
                if (next >= text.length || !isKeyStart(next)) return false
                val bare = isBare(text[next])
                end = if (bare) bareEnd(next) else string(next, singleLine = true)
                if (!bare && !closed) return false
                allBare = allBare && bare
                parts++
                next = blanksEnd(end)
                if (next >= text.length || text[next] != '.') break
                next = blanksEnd(next + 1)
            }
            val number = allBare && (text[first].isAsciiDigit() || text[first] == '-')
            val word = parts == 1 && allBare && end - first <= 5 && text.substring(first, end) in VALUE_WORDS
            if (number || word || (parts == 1 && !allBare)) return false
            val closing = if (double) "]]" else "]"
            if (!text.startsWith(closing, next)) return false
            next += closing.length
            while (next < text.length && (text[next] == ' ' || text[next] == '\t' || text[next] == '\r')) next++
            return next >= text.length || text[next] == '\n' || text[next] == '#'
        }

        fun punctuationOrValue(c: Char) {
            when (c) {
                '[' -> {
                    open.addLast(false)
                    keyNext = false
                    i++
                }

                '{' -> {
                    open.addLast(true)
                    tables++
                    keyNext = true
                    i++
                }

                ']', '}' -> {
                    close(table = c == '}')
                    keyNext = false
                    i++
                }

                ',' -> {
                    keyNext = open.lastOrNull() == true
                    i++
                }

                else -> {
                    if (isValue(c)) value() else i++
                    keyNext = false
                }
            }
        }

        /** Closes the innermost inline table or array and whatever is open inside it; nothing if none is open. */
        fun close(table: Boolean) {
            if (if (table) tables == 0 else tables == open.size) return
            while (true) {
                val last = open.removeLast()
                if (last) tables--
                if (last == table) return
            }
        }

        /** A number, date, time, `inf`, `nan` or boolean; any other word is left unstyled. */
        fun value() {
            val start = i
            var end = valueEnd(i)
            // An offset or local date-time may separate the date from the time by a space: `1979-05-27 07:32:00`.
            if (end - start == 10 && isDate(start) && end + 3 < text.length && text[end] == ' ' &&
                text[end + 1].isAsciiDigit() && text[end + 2].isAsciiDigit() && text[end + 3] == ':'
            ) {
                end = valueEnd(end + 1)
            }
            val unsigned = if (text[start] == '+' || text[start] == '-') start + 1 else start
            val style =
                when {
                    isWord(start, end, "true") || isWord(start, end, "false") -> TokenStyle.KEYWORD
                    isWord(unsigned, end, "inf") || isWord(unsigned, end, "nan") -> TokenStyle.NUMBER
                    unsigned < end && text[unsigned].isAsciiDigit() -> TokenStyle.NUMBER
                    else -> null
                }
            if (style != null) add(start, end, style)
            i = end
        }

        fun isWord(
            start: Int,
            end: Int,
            word: String,
        ) = end - start == word.length && text.startsWith(word, start)

        fun isDate(start: Int): Boolean =
            (0 until 10).all { k ->
                if (k == 4 || k == 7) text[start + k] == '-' else text[start + k].isAsciiDigit()
            }

        fun blanksEnd(from: Int): Int {
            var end = from
            while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
            return end
        }

        fun bareEnd(from: Int): Int {
            var end = from
            while (end < text.length && isBare(text[end])) end++
            return end
        }

        fun valueEnd(from: Int): Int {
            var end = from
            while (end < text.length && isValue(text[end])) end++
            return end
        }

        /** Whether the quote at [at] is the first of three. */
        fun isTriple(at: Int): Boolean = at + 2 < text.length && text[at + 1] == text[at] && text[at + 2] == text[at]

        /**
         * The end of the string starting at [start] with `"`, `"""`, `'` or `'''` (only the single-line forms if
         * [singleLine]); [closed] tells whether its quotes closed it. Single-line strings end at the end of their line,
         * multi-line ones at the end of the text if not closed; up to two quotes before the closing three belong to
         * the content (`""""`).
         */
        fun string(
            start: Int,
            singleLine: Boolean = false,
        ): Int {
            val quote = text[start]
            val basic = quote == '"'
            val multiLine = !singleLine && isTriple(start)
            var end = start + if (multiLine) 3 else 1
            closed = false
            while (end < text.length) {
                val c = text[end]
                when {
                    basic && c == '\\' -> {
                        if (!multiLine && end + 1 < text.length && text[end + 1] == '\n') return end + 1
                        end += 2
                    }

                    c == quote && !multiLine -> {
                        closed = true
                        return end + 1
                    }

                    c == quote && isTriple(end) -> {
                        end += 3
                        var extra = 0
                        while (extra < 2 && end < text.length && text[end] == quote) {
                            end++
                            extra++
                        }
                        closed = true
                        return end
                    }

                    c == '\n' && !multiLine -> {
                        return end
                    }

                    else -> {
                        end++
                    }
                }
            }
            return minOf(end, text.length)
        }
    }

    /** Words that are values, not keys. */
    private val VALUE_WORDS = setOf("true", "false", "inf", "nan")

    /** Characters of bare keys. */
    private fun isBare(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

    /** Characters of numbers, dates, times and booleans: bare ones and `+ . :`. */
    private fun isValue(c: Char) = isBare(c) || c == '+' || c == '.' || c == ':'

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
