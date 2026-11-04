package dev.sluide.speech.text

/**
 * The inline level of [SpeechText.prepare] for Markdown: removes emphasis, strong and strike markers that pair up
 * (CommonMark's flanking rules, so `2 * 3` and `snake_case` stay), the backticks of inline code, link targets,
 * footnote markers, HTML tags and comments, autolinks and bare URLs; keeps link text, image alt text and escaped
 * chars.
 *
 * Linear in the text and without recursion: bracket partners, backtick runs and comment ends are found in one pass
 * up front, and link texts are scopes on an explicit stack.
 */
internal object MarkdownInline {
    fun emit(
        text: InlineText,
        out: SpokenTextBuilder,
    ) {
        for (token in Resolver(text).tokens()) {
            when (token) {
                is Literal -> {
                    out.char(token.char, token.source)
                }

                is Delimiter -> {
                    if (!token.matched) {
                        for (offset in 0 until token.count) out.char(token.char, text.source(token.index + offset))
                    }
                }
            }
        }
    }

    private sealed interface Token

    private class Literal(
        val char: Char,
        val source: Int,
    ) : Token

    /** A run of `*`, `_` or `~` at [index] in the text; dropped when it pairs with another run, else spoken. */
    private class Delimiter(
        val char: Char,
        val index: Int,
        val count: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
    ) : Token {
        var matched = false

        /** Which runs pair with each other: the same char, and for `~` the same length. */
        val kind: Int
            get() =
                when (char) {
                    '*' -> 0
                    '_' -> 1
                    else -> 1 + count
                }
    }

    /**
     * The part of the text being read: the whole text or a link's text, from [start] to [end]; after it the reading
     * goes on at [resume]. Its markers pair only with each other, each kind on its own stack of open runs.
     */
    private class Scope(
        val start: Int,
        val end: Int,
        val resume: Int,
    ) {
        val openers = List(KINDS) { ArrayList<Delimiter>() }

        fun add(delimiter: Delimiter) {
            if (!delimiter.canOpen && !delimiter.canClose) return
            val own = openers[delimiter.kind]
            if (delimiter.canClose && own.isNotEmpty()) {
                val opener = own.removeLast()
                opener.matched = true
                delimiter.matched = true
                // Runs opened between the pair stay unpaired.
                for (other in openers) {
                    while (other.isNotEmpty() &&
                        other.last().index > opener.index
                    ) {
                        other.removeLast()
                    }
                }
            } else if (delimiter.canOpen) {
                own += delimiter
            }
        }
    }

    private class Resolver(
        private val text: InlineText,
    ) {
        private val length = text.length
        private val partners = partners()
        private val nextSameTicks = nextSameTicks()
        private val nextCommentClose = nextCommentClose()
        private val tokens = ArrayList<Token>()
        private val scopes = ArrayDeque<Scope>()
        private var scope = Scope(0, length, length)

        fun tokens(): List<Token> {
            var index = 0
            while (true) {
                while (index >= scope.end) {
                    index = scope.resume
                    scope = scopes.removeLastOrNull() ?: return tokens
                }
                index = token(index)
            }
        }

        /** Reads the token at [index] and returns the index after it. */
        private fun token(index: Int): Int {
            val char = text[index]
            val next = if (index + 1 < scope.end) text[index + 1] else null
            return when {
                char == '\\' && next != null && (next == '\n' || next in ASCII_PUNCTUATION) -> {
                    tokens += Literal(next, text.source(index + 1))
                    index + 2
                }

                char == '`' -> {
                    codeSpan(index)
                }

                char == '!' && next == '[' && openLink(index + 1) -> {
                    index + 2
                }

                char == '[' && openLink(index) -> {
                    index + 1
                }

                char == '[' && next == '^' && partner(index) != null -> {
                    partnerOf(index) + 1
                }

                char == '<' -> {
                    skippedTag(index) ?: literal(index)
                }

                char == 'h' || char == 'w' -> {
                    bareUrlEnd(index) ?: literal(index)
                }

                char == '*' || char == '_' || char == '~' -> {
                    delimiter(index)
                }

                else -> {
                    literal(index)
                }
            }
        }

        private fun literal(index: Int): Int {
            tokens += Literal(text[index], text.source(index))
            return index + 1
        }

        /** A code span keeps its text as is; backticks without a closing run of the same length are spoken. */
        private fun codeSpan(index: Int): Int {
            val ticks = run(index, '`')
            val closing = if (index == 0 || text[index - 1] != '`') nextSameTicks[index] else -1
            if (closing in 0 until scope.end) {
                for (code in index + ticks until closing) tokens += Literal(text[code], text.source(code))
                return closing + ticks
            }
            for (tick in index until index + ticks) tokens += Literal('`', text.source(tick))
            return index + ticks
        }

        /**
         * Opens a scope for the text of `[text](target "title")` or `[text][ref]` at [open], reading on after the
         * link once it is done; false for brackets that are no link.
         */
        private fun openLink(open: Int): Boolean {
            val close = partner(open) ?: return false
            val after = close + 1
            val end =
                if (after < scope.end && (text[after] == '(' || text[after] == '[')) partner(after) else null
            if (end == null) return false
            scopes.addLast(scope)
            scope = Scope(open + 1, close, end + 1)
            return true
        }

        /** The partner of the bracket or parenthesis at [open] within the scope, or null. */
        private fun partner(open: Int): Int? = partnerOf(open).takeIf { it in open + 1 until scope.end }

        private fun partnerOf(open: Int): Int = partners[open]

        /** The index after an HTML tag, HTML comment or autolink at [index], all skipped; null for a plain `<`. */
        private fun skippedTag(index: Int): Int? {
            if (text.startsWith("<!--", index)) {
                val close = if (index + COMMENT_OPEN < length) nextCommentClose[index + COMMENT_OPEN] else -1
                return if (close >= 0 && close + COMMENT_CLOSE <= scope.end) close + COMMENT_CLOSE else null
            }
            var close = index + 1
            while (close < scope.end && text[close] != '>' && text[close] != '<') close++
            if (close >= scope.end || text[close] != '>') return null
            val inner = text.substring(index, close + 1)
            return if (TAG.matches(inner) || AUTOLINK.matches(inner) || EMAIL.matches(inner)) close + 1 else null
        }

        /** The index after a bare `http://`, `https://` or `www.` URL at [index], or null; GFM's trailing chars. */
        private fun bareUrlEnd(index: Int): Int? {
            if (index > scope.start && text[index - 1].isLetterOrDigit()) return null
            if (URL_STARTS.none { text.startsWith(it, index) }) return null
            var end = index
            var opened = 0
            var closed = 0
            while (end < scope.end && !text[end].isWhitespace() && text[end] != '<') {
                if (text[end] == '(') opened++
                if (text[end] == ')') closed++
                end++
            }
            while (end > index) {
                val last = text[end - 1]
                if (last in URL_TRAILING) {
                    end--
                } else if (last == ')' && closed > opened) {
                    closed--
                    end--
                } else {
                    break
                }
            }
            return end.takeIf { it > index + URL_MIN_LENGTH }
        }

        private fun delimiter(index: Int): Int {
            val char = text[index]
            val count = run(index, char)
            val before = if (index > scope.start) text[index - 1] else ' '
            val after = if (index + count < scope.end) text[index + count] else ' '
            val leftFlanking =
                !after.isWhitespace() && (!isPunctuation(after) || before.isWhitespace() || isPunctuation(before))
            val rightFlanking =
                !before.isWhitespace() && (!isPunctuation(before) || after.isWhitespace() || isPunctuation(after))
            val usable = char != '~' || count <= 2
            val canOpen = usable && leftFlanking && (char != '_' || !rightFlanking || isPunctuation(before))
            val canClose = usable && rightFlanking && (char != '_' || !leftFlanking || isPunctuation(after))
            val delimiter = Delimiter(char, index, count, canOpen, canClose)
            tokens += delimiter
            scope.add(delimiter)
            return index + count
        }

        private fun run(
            start: Int,
            char: Char,
        ): Int {
            var index = start
            while (index < scope.end && text[index] == char) index++
            return index - start
        }

        /** For each `[` and `(`, the index of the `]` or `)` that balances it, skipping escaped chars; else -1. */
        private fun partners(): IntArray {
            val partners = IntArray(length) { -1 }
            val brackets = ArrayList<Int>()
            val parens = ArrayList<Int>()
            var index = 0
            while (index < length) {
                when (text[index]) {
                    '\\' -> index++
                    '[' -> brackets += index
                    '(' -> parens += index
                    ']' -> brackets.removeLastOrNull()?.let { partners[it] = index }
                    ')' -> parens.removeLastOrNull()?.let { partners[it] = index }
                }
                index++
            }
            return partners
        }

        /** For the start of each backtick run, the start of the next run of the same length; else -1. */
        private fun nextSameTicks(): IntArray {
            val next = IntArray(length) { -1 }
            val following = HashMap<Int, Int>()
            var end = length
            while (end > 0) {
                if (text[end - 1] != '`') {
                    end--
                    continue
                }
                var start = end - 1
                while (start > 0 && text[start - 1] == '`') start--
                next[start] = following[end - start] ?: -1
                following[end - start] = start
                end = start
            }
            return next
        }

        /** For each index, the first `-->` at or after it; else -1. */
        private fun nextCommentClose(): IntArray {
            val next = IntArray(length + 1) { -1 }
            for (index in length - 1 downTo 0) {
                next[index] = if (text.startsWith("-->", index)) index else next[index + 1]
            }
            return next
        }
    }

    private fun isPunctuation(char: Char): Boolean = !char.isLetterOrDigit() && !char.isWhitespace()

    private const val KINDS = 4
    private const val COMMENT_OPEN = 4
    private const val COMMENT_CLOSE = 3
    private const val ASCII_PUNCTUATION = """!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~"""
    private const val URL_MIN_LENGTH = 4
    private val URL_STARTS = listOf("https://", "http://", "www.")
    private const val URL_TRAILING = "?!.,:*_~'\""
    private val TAG = Regex("""</?[A-Za-z][A-Za-z0-9-]*(\s[^<>]*)?/?>""")
    private val AUTOLINK = Regex("""<[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s<>]*>""")
    private val EMAIL = Regex("""<[^\s@<>]+@[^\s@<>]+>""")
}
