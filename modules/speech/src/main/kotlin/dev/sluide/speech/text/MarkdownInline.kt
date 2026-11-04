package dev.sluide.speech.text

/**
 * The inline level of [SpeechText.prepare] for Markdown: removes emphasis, strong and strike markers that pair up
 * (CommonMark's flanking rules, so `2 * 3` and `snake_case` stay), the backticks of inline code, link targets, HTML
 * tags and comments, autolinks and bare URLs; keeps link text, image alt text and escaped chars.
 */
internal object MarkdownInline {
    fun emit(
        text: InlineText,
        out: SpokenTextBuilder,
    ) {
        for (literal in resolve(text, 0, text.length)) out.char(literal.char, literal.source)
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
    }

    private class Link(
        val textStart: Int,
        val textEnd: Int,
        val end: Int,
    )

    /** The spoken chars of [from] to [to]; link texts are resolved on their own, so their markers pair inside. */
    private fun resolve(
        text: InlineText,
        from: Int,
        to: Int,
    ): List<Literal> {
        val tokens = mutableListOf<Token>()
        var index = from
        while (index < to) index = token(text, index, from, to, tokens)
        pair(tokens.filterIsInstance<Delimiter>())
        return tokens.flatMap { token ->
            when (token) {
                is Literal -> listOf(token)
                is Delimiter if token.matched -> emptyList()
                is Delimiter -> (0 until token.count).map { Literal(token.char, text.source(token.index + it)) }
            }
        }
    }

    /** Reads the token at [index] into [tokens] and returns the index after it. */
    private fun token(
        text: InlineText,
        index: Int,
        from: Int,
        to: Int,
        tokens: MutableList<Token>,
    ): Int {
        val char = text[index]
        val next = if (index + 1 < to) text[index + 1] else null
        val literal = Literal(char, text.source(index))
        return when {
            char == '\\' && next != null && (next == '\n' || next in ASCII_PUNCTUATION) -> {
                tokens += Literal(next, text.source(index + 1))
                index + 2
            }

            char == '`' -> {
                codeSpan(text, index, to, tokens)
            }

            char == '!' && next == '[' -> {
                link(text, index + 1, to)?.let { link ->
                    tokens += resolve(text, link.textStart, link.textEnd)
                    link.end
                } ?: run {
                    tokens += literal
                    index + 1
                }
            }

            char == '[' -> {
                link(text, index, to)?.let { link ->
                    tokens += resolve(text, link.textStart, link.textEnd)
                    link.end
                } ?: run {
                    tokens += literal
                    index + 1
                }
            }

            char == '<' -> {
                skippedTag(text, index, to) ?: run {
                    tokens += literal
                    index + 1
                }
            }

            char == 'h' || char == 'w' -> {
                bareUrlEnd(text, index, from, to) ?: run {
                    tokens += literal
                    index + 1
                }
            }

            char == '*' || char == '_' || char == '~' -> {
                delimiter(text, index, from, to, tokens)
            }

            else -> {
                tokens += literal
                index + 1
            }
        }
    }

    /** A code span keeps its text as is; backticks without a closing run of the same length are spoken. */
    private fun codeSpan(
        text: InlineText,
        index: Int,
        to: Int,
        tokens: MutableList<Token>,
    ): Int {
        val ticks = run(text, index, to, '`')
        var search = index + ticks
        while (search < to) {
            if (text[search] != '`') {
                search++
                continue
            }
            val closing = run(text, search, to, '`')
            if (closing == ticks) {
                for (code in index + ticks until search) tokens += Literal(text[code], text.source(code))
                return search + closing
            }
            search += closing
        }
        for (tick in index until index + ticks) tokens += Literal('`', text.source(tick))
        return index + ticks
    }

    /** `[text](target "title")` or `[text][ref]` at [open]; null for brackets that are no link. */
    private fun link(
        text: InlineText,
        open: Int,
        to: Int,
    ): Link? {
        val close = closing(text, open, to, '[', ']') ?: return null
        val after = close + 1
        val end =
            when (if (after < to) text[after] else null) {
                '(' -> closing(text, after, to, '(', ')')
                '[' -> closing(text, after, to, '[', ']')
                else -> null
            } ?: return null
        return Link(open + 1, close, end + 1)
    }

    /** The index of the [closeChar] that balances the [openChar] at [open], skipping escaped chars. */
    private fun closing(
        text: InlineText,
        open: Int,
        to: Int,
        openChar: Char,
        closeChar: Char,
    ): Int? {
        var depth = 0
        var index = open
        while (index < to) {
            when (text[index]) {
                '\\' -> index++
                openChar -> depth++
                closeChar -> if (--depth == 0) return index
            }
            index++
        }
        return null
    }

    /** The index after an HTML tag, HTML comment or autolink at [index], all skipped; null for a plain `<`. */
    private fun skippedTag(
        text: InlineText,
        index: Int,
        to: Int,
    ): Int? {
        if (text.startsWith("<!--", index)) {
            val end = text.substring(index, to).indexOf("-->", 4)
            return if (end < 0) null else index + end + 3
        }
        val close = (index + 1 until to).firstOrNull { text[it] == '>' || text[it] == '<' } ?: return null
        if (text[close] != '>') return null
        val inner = text.substring(index, close + 1)
        return if (TAG.matches(inner) || AUTOLINK.matches(inner) || EMAIL.matches(inner)) close + 1 else null
    }

    /** The index after a bare `http://`, `https://` or `www.` URL at [index], or null; GFM's trailing-char rules. */
    private fun bareUrlEnd(
        text: InlineText,
        index: Int,
        from: Int,
        to: Int,
    ): Int? {
        if (index > from && text[index - 1].isLetterOrDigit()) return null
        if (URL_STARTS.none { text.startsWith(it, index) }) return null
        var end = index
        while (end < to && !text[end].isWhitespace() && text[end] != '<') end++
        while (end > index) {
            val last = text[end - 1]
            val url = text.substring(index, end)
            val unbalanced = last == ')' && url.count { it == ')' } > url.count { it == '(' }
            if (last in URL_TRAILING || unbalanced) end-- else break
        }
        return end.takeIf { it > index + URL_MIN_LENGTH }
    }

    private fun delimiter(
        text: InlineText,
        index: Int,
        from: Int,
        to: Int,
        tokens: MutableList<Token>,
    ): Int {
        val char = text[index]
        val count = run(text, index, to, char)
        val before = if (index > from) text[index - 1] else ' '
        val after = if (index + count < to) text[index + count] else ' '
        val leftFlanking =
            !after.isWhitespace() && (!isPunctuation(after) || before.isWhitespace() || isPunctuation(before))
        val rightFlanking =
            !before.isWhitespace() && (!isPunctuation(before) || after.isWhitespace() || isPunctuation(after))
        val usable = char != '~' || count <= 2
        val canOpen = usable && leftFlanking && (char != '_' || !rightFlanking || isPunctuation(before))
        val canClose = usable && rightFlanking && (char != '_' || !leftFlanking || isPunctuation(after))
        tokens += Delimiter(char, index, count, canOpen, canClose)
        return index + count
    }

    /** Pairs each closing run with the nearest open run of the same char; runs between them stay unpaired. */
    private fun pair(delimiters: List<Delimiter>) {
        val openers = mutableListOf<Delimiter>()
        for (delimiter in delimiters) {
            val opener =
                if (delimiter.canClose) {
                    openers.indexOfLast { it.char == delimiter.char && (it.char != '~' || it.count == delimiter.count) }
                } else {
                    -1
                }
            if (opener >= 0) {
                openers[opener].matched = true
                delimiter.matched = true
                while (openers.size > opener) openers.removeLast()
            } else if (delimiter.canOpen) {
                openers += delimiter
            }
        }
    }

    private fun run(
        text: InlineText,
        start: Int,
        to: Int,
        char: Char,
    ): Int {
        var index = start
        while (index < to && text[index] == char) index++
        return index - start
    }

    private fun isPunctuation(char: Char): Boolean = !char.isLetterOrDigit() && !char.isWhitespace()

    private const val ASCII_PUNCTUATION = """!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~"""
    private const val URL_MIN_LENGTH = 4
    private val URL_STARTS = listOf("https://", "http://", "www.")
    private const val URL_TRAILING = "?!.,:*_~'\""
    private val TAG = Regex("""</?[A-Za-z][A-Za-z0-9-]*(\s[^<>]*)?/?>""")
    private val AUTOLINK = Regex("""<[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s<>]*>""")
    private val EMAIL = Regex("""<[^\s@<>]+@[^\s@<>]+>""")
}
