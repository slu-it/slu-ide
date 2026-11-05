package dev.sluide.translation.text

/**
 * The spans of one stretch (a line, a table cell, or a paragraph wrapped over lines) that must not reach the translation model: bare URLs and e-mail addresses (both
 * modes) and, in Markdown, inline code, autolinks, inline HTML tags and comments, footnote references and the
 * `[`/`![` and `](target)`/`][ref]` parts of links and images (the link text stays out of the spans). Backslash
 * escapes are skipped, so an escaped bracket starts nothing.
 *
 * Linear in the stretch: bracket partners and code-span closers are found in one pass, every other search is bounded
 * by a small limit or by a cached position. Spans never cross the end of the stretch.
 */
internal object InlineSpans {
    /** The spans in [start] to [end] (exclusive) of [text] as sorted, disjoint inclusive ranges. */
    fun find(
        text: String,
        start: Int,
        end: Int,
        markdown: Boolean,
    ): List<IntRange> = Scanner(text, start, end, markdown).spans()

    private const val MAX_TARGET = 2000
    private const val MAX_LABEL = 500
    private const val NOT_SEARCHED = -2

    private class Scanner(
        private val text: String,
        private val start: Int,
        private val end: Int,
        private val markdown: Boolean,
    ) {
        private val spans = ArrayList<IntRange>()
        private var spanEnd = start
        private var closers: HashMap<Int, Int>? = null
        private var partners: IntArray? = null
        private var tickClosers: HashMap<Int, Int>? = null
        private var commentClose = NOT_SEARCHED
        private var escapedOpen = false

        fun spans(): List<IntRange> {
            var i = start
            while (i < end) {
                val c = text[i]
                i =
                    when {
                        markdown && c == '\\' -> escape(i)
                        markdown && c == '`' -> codeSpan(i)
                        markdown && c == '!' && i + 1 < end && text[i + 1] == '[' -> link(i, i + 1)
                        markdown && c == '[' -> link(i, i)
                        markdown && c == ']' -> closer(i)
                        markdown && c == '<' -> angle(i)
                        c == 'h' || c == 'H' || c == 'w' || c == 'W' -> url(i)
                        c == '@' -> email(i)
                        else -> i + 1
                    }
            }
            return spans
        }

        /** A backslash escape stays text; an escaped `[` also switches off the standalone `](target)` search. */
        private fun escape(i: Int): Int {
            if (i + 1 < end && text[i + 1] == '[') escapedOpen = true
            return i + 2
        }

        private fun add(
            from: Int,
            to: Int,
        ) {
            spans += from until to
            spanEnd = to
        }

        private fun codeSpan(i: Int): Int {
            var run = i
            while (run < end && text[run] == '`') run++
            val closing = tickClosers()[i] ?: return run
            val closeEnd = closing + (run - i)
            add(i, closeEnd)
            return closeEnd
        }

        /** Start of each backtick run to the start of the next run of the same length. */
        private fun tickClosers(): HashMap<Int, Int> {
            tickClosers?.let { return it }
            val starts = ArrayList<Int>()
            val lengths = ArrayList<Int>()
            var i = start
            while (i < end) {
                if (text[i] != '`') {
                    i++
                    continue
                }
                var run = i
                while (run < end && text[run] == '`') run++
                // A backslash escapes the first backtick; the scanner starts a run after it.
                val escaped = if (i > start && text[i - 1] == '\\') 1 else 0
                if (run - i > escaped) {
                    starts += i + escaped
                    lengths += run - i - escaped
                }
                i = run
            }
            val closers = HashMap<Int, Int>()
            val latest = HashMap<Int, Int>()
            for (k in starts.indices.reversed()) {
                latest[lengths[k]]?.let { closers[starts[k]] = it }
                latest[lengths[k]] = starts[k]
            }
            tickClosers = closers
            return closers
        }

        private fun partner(open: Int): Int {
            val known = partners ?: computePartners().also { partners = it }
            return known[open - start]
        }

        private fun computePartners(): IntArray {
            val result = IntArray(end - start) { -1 }
            val stack = ArrayList<Int>()
            var i = start
            while (i < end) {
                when (text[i]) {
                    '\\' -> i++
                    '[' -> stack += i
                    ']' -> if (stack.isNotEmpty()) result[stack.removeLast() - start] = i
                }
                i++
            }
            return result
        }

        /** A link or image whose `[` is at [open] (the span starts at [from], one before for an image). */
        private fun link(
            from: Int,
            open: Int,
        ): Int {
            val close = partner(open)
            if (close < 0) return from + 1
            if (from == open && open + 1 < close && text[open + 1] == '^' &&
                (open + 2 until close).none { text[it].isWhitespace() }
            ) {
                add(open, close + 1)
                return close + 1
            }
            val after = close + 1
            val targetEnd =
                when {
                    after >= end -> -1
                    text[after] == '(' -> target(after)
                    text[after] == '[' -> label(after)
                    else -> -1
                }
            if (targetEnd < 0) return from + 1
            add(from, open + 1)
            (closers ?: HashMap<Int, Int>().also { closers = it })[close] = targetEnd
            return open + 1
        }

        /** The `](target)` or `][ref]` part at a `]`; also standalone, for a link text that began on an earlier line (a stretch of one line). */
        private fun closer(i: Int): Int {
            closers?.remove(i)?.let {
                add(i, it)
                return it
            }
            if (escapedOpen) return i + 1
            val next = if (i + 1 < end) text[i + 1] else ' '
            val targetEnd =
                when (next) {
                    '(' -> target(i + 1)
                    '[' -> label(i + 1)
                    else -> -1
                }
            if (targetEnd < 0) return i + 1
            add(i, targetEnd)
            return targetEnd
        }

        /** The exclusive end of the parenthesized target at [open], balanced, with quoted titles; -1 if unclosed. */
        private fun target(open: Int): Int {
            val limit = minOf(end, open + MAX_TARGET)
            var depth = 1
            var quote = '\u0000'
            var j = open + 1
            while (j < limit) {
                val c = text[j]
                when {
                    c == '\\' -> j++
                    quote != '\u0000' -> if (c == quote) quote = '\u0000'
                    (c == '"' || c == '\'') && text[j - 1].isWhitespace() -> quote = c
                    c == '(' -> depth++
                    c == ')' -> if (--depth == 0) return j + 1
                }
                j++
            }
            return -1
        }

        private fun label(open: Int): Int {
            val limit = minOf(end, open + MAX_LABEL)
            for (j in open + 1 until limit) {
                if (text[j] == ']') return j + 1
                if (text[j] == '[') return -1
            }
            return -1
        }

        private fun angle(i: Int): Int {
            if (text.startsWith("<!--", i)) {
                if (commentClose == NOT_SEARCHED || (commentClose >= 0 && commentClose < i + 4)) {
                    commentClose = indexIn("-->", i + 4)
                }
                if (commentClose < 0) return i + 1
                add(i, commentClose + 3)
                return commentClose + 3
            }
            val tagEnd = HtmlTags.tagEnd(text, i, end)
            if (tagEnd < 0) return i + 1
            add(i, tagEnd)
            return tagEnd
        }

        private fun indexIn(
            marker: String,
            from: Int,
        ): Int {
            for (i in from..end - marker.length) if (text.startsWith(marker, i)) return i
            return -1
        }

        private fun url(i: Int): Int {
            if (i > start && text[i - 1].isLetterOrDigit()) return i + 1
            val scheme =
                when {
                    text.regionMatches(i, "https://", 0, 8, ignoreCase = true) -> 8
                    text.regionMatches(i, "http://", 0, 7, ignoreCase = true) -> 7
                    text.regionMatches(i, "www.", 0, 4, ignoreCase = true) -> 4
                    else -> return i + 1
                }
            // "www." needs an alphanumeric after it; checked before the scan so "www.!www.!" stays linear.
            if (scheme == 4 && (i + 4 >= end || !text[i + 4].isLetterOrDigit())) return i + 1
            var j = i + scheme
            var opens = 0
            var closes = 0
            while (j < end) {
                val c = text[j]
                if (c.isWhitespace() || c == '<' || c == '>' || c == '"' || (markdown && c == ']')) break
                if (c == '(') {
                    opens++
                } else if (c == ')') {
                    closes++
                }
                j++
            }
            while (j > i + scheme) {
                val c = text[j - 1]
                when {
                    c in ".,;:!?'" || (markdown && (c == '*' || c == '~')) -> {
                        j--
                    }

                    c == ')' && closes > opens -> {
                        closes--
                        j--
                    }

                    else -> {
                        break
                    }
                }
            }
            if (j <= i + scheme) return i + 1
            add(i, j)
            return j
        }

        private fun email(i: Int): Int {
            var from = i
            while (from > spanEnd && isLocal(text[from - 1])) from--
            if (from == i) return i + 1
            var j = i + 1
            while (j < end && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '.')) j++
            while (j > i + 1 && (text[j - 1] == '.' || text[j - 1] == '-')) j--
            val lastDot = (j - 1 downTo i + 1).firstOrNull { text[it] == '.' } ?: return i + 1
            if (lastDot == i + 1 || j - lastDot - 1 < 2 || (lastDot + 1 until j).any { !text[it].isLetter() }) {
                return i + 1
            }
            add(from, j)
            return j
        }

        private fun isLocal(c: Char) = c.isLetterOrDigit() || c in "._%+-"
    }
}
