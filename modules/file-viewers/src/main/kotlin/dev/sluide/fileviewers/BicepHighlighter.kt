package dev.sluide.fileviewers

/**
 * Highlights Bicep (`.bicep`, `.bicepparam`), which has no RSyntaxTextArea lexer: our own tokenizer ([BicepTokens])
 * in one linear pass, then the roles of words from their neighbours ([BicepTokens.roles]). The resource graph
 * ([BicepGraph]) reads the same tokens.
 *
 * Styles: line and block comments; strings (`'…'` with `\` escapes, `'''…'''` without), whose `${…}` interpolations
 * are code between `${` and `}` operators; keywords; the types `string int bool object array` and the name declared
 * by `type`; decorators (`@description`, `@sys.description`); object keys (a word or quoted key before `:` that
 * starts an object line, or follows `{`, `,` or a decorator) as `property`; a word directly followed by `(` as
 * `builtin`; numbers; the operators `?? ?. .? ! == != =~ !~ && || ? : =>` (`.?` is Bicep's safe dereference). Words
 * after `.`, `?.`, `.?` or `::` are members, never keywords; the name after a spread's `...` is none.
 * Nothing is flagged as an error: an unterminated `'…'` string ends with its line, `'''…'''` and block comments
 * with the text.
 */
internal object BicepHighlighter : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        val tokens = BicepTokens.lex(text)
        val roles = tokens.roles()
        val spans = ArrayList<HighlightSpan>(tokens.size)
        for (i in 0 until tokens.size) {
            val style =
                when (tokens.kind(i)) {
                    BicepTokens.COMMENT -> {
                        TokenStyle.COMMENT
                    }

                    BicepTokens.STRING, BicepTokens.MULTILINE_STRING -> {
                        if (roles[i] == BicepTokens.ROLE_PROPERTY) TokenStyle.PROPERTY else TokenStyle.STRING
                    }

                    BicepTokens.INTERPOLATION_START, BicepTokens.INTERPOLATION_END, BicepTokens.OPERATOR -> {
                        TokenStyle.OPERATOR
                    }

                    BicepTokens.NUMBER -> {
                        TokenStyle.NUMBER
                    }

                    BicepTokens.DECORATOR -> {
                        TokenStyle.ANNOTATION
                    }

                    BicepTokens.IDENTIFIER -> {
                        identifierStyles[roles[i].toInt()]
                    }

                    else -> {
                        null
                    }
                }
            if (style != null) spans += HighlightSpan(tokens.start(i), tokens.end(i), style)
        }
        return spans
    }

    /** The style of an identifier per role ([BicepTokens.ROLE_NONE] …). */
    private val identifierStyles =
        arrayOf(null, null, TokenStyle.KEYWORD, TokenStyle.TYPE, TokenStyle.BUILTIN, TokenStyle.PROPERTY)
}

/**
 * The tokens of a Bicep [text], in order and without gaps other than whitespace: every character outside whitespace
 * belongs to a token. Kinds are the constants below, kept in arrays so that large files stay cheap.
 */
internal class BicepTokens private constructor(
    val text: String,
) {
    var size = 0
        private set
    private var kinds = ByteArray(INITIAL_CAPACITY)
    private var starts = IntArray(INITIAL_CAPACITY)
    private var ends = IntArray(INITIAL_CAPACITY)

    /** Whether a line break lies between the token and the last token before it that is no comment (or none is). */
    private var lineStarts = BooleanArray(INITIAL_CAPACITY)

    fun kind(i: Int): Byte = kinds[i]

    fun start(i: Int): Int = starts[i]

    fun end(i: Int): Int = ends[i]

    fun startsLine(i: Int): Boolean = lineStarts[i]

    /** The token's text. */
    fun text(i: Int): String = text.substring(starts[i], ends[i])

    /** Whether the token is the single character [c] (punctuation or an operator). */
    fun isChar(
        i: Int,
        c: Char,
    ): Boolean = ends[i] - starts[i] == 1 && text[starts[i]] == c

    /** Whether the token's text is [word]. */
    fun isWord(
        i: Int,
        word: String,
    ): Boolean = ends[i] - starts[i] == word.length && text.startsWith(word, starts[i])

    private fun add(
        kind: Byte,
        start: Int,
        end: Int,
        lineStart: Boolean,
    ) {
        if (size == kinds.size) {
            val capacity = size * 2
            kinds = kinds.copyOf(capacity)
            starts = starts.copyOf(capacity)
            ends = ends.copyOf(capacity)
            lineStarts = lineStarts.copyOf(capacity)
        }
        kinds[size] = kind
        starts[size] = start
        ends[size] = end
        lineStarts[size] = lineStart
        size++
    }

    /**
     * The role of each token: for identifiers what they are ([ROLE_KEYWORD], [ROLE_TYPE], [ROLE_BUILTIN] for a
     * function, [ROLE_PROPERTY] for an object key, [ROLE_MEMBER] after `.`, `?.`, `.?` or `::`, else [ROLE_NONE], a
     * reference to a symbol or a local name), for quoted keys [ROLE_PROPERTY]. Comments are skipped: a key may have
     * one before it.
     */
    fun roles(): ByteArray {
        val roles = ByteArray(size)
        // The open brackets, innermost last: `{`, `[`, `(`, `d` for the `(` of a decorator, `$` for an interpolation.
        val brackets = StringBuilder()
        var previous = -1
        // The `)` that closed a decorator's arguments last: a key may follow it (`{ @minValue(1) a: int }`).
        var decoratorEnd = -1
        for (i in 0 until size) {
            val kind = kinds[i]
            if (kind == COMMENT) continue
            when (kind) {
                PUNCTUATION -> {
                    val c = text[starts[i]]
                    if (ends[i] - starts[i] == 1 && (c == '{' || c == '[' || c == '(')) {
                        val decorator = c == '(' && previous >= 0 && kinds[previous] == DECORATOR
                        brackets.append(if (decorator) 'd' else c)
                    }
                    if ((c == '}' || c == ']' || c == ')') && brackets.isNotEmpty()) {
                        if (brackets[brackets.length - 1] == 'd') decoratorEnd = i
                        brackets.setLength(brackets.length - 1)
                    }
                }

                INTERPOLATION_START -> {
                    brackets.append('$')
                }

                INTERPOLATION_END -> {
                    if (brackets.isNotEmpty()) brackets.setLength(brackets.length - 1)
                }

                IDENTIFIER, STRING -> {
                    roles[i] = role(i, previous, brackets, decoratorEnd)
                }
            }
            previous = i
        }
        return roles
    }

    private fun role(
        i: Int,
        previous: Int,
        brackets: CharSequence,
        decoratorEnd: Int,
    ): Byte {
        val next = nextSignificant(i)
        if (previous >= 0 && isMemberAccess(previous)) {
            return if (kinds[i] == IDENTIFIER && directlyFollowedBy(i, '(')) ROLE_BUILTIN else ROLE_MEMBER
        }
        if (isKey(i, previous, next, brackets, decoratorEnd)) return ROLE_PROPERTY
        if (kinds[i] == STRING) return ROLE_NONE
        val word = text(i)
        if (word in KEYWORDS) return ROLE_KEYWORD
        if (directlyFollowedBy(i, '(')) return ROLE_BUILTIN
        if (word in TYPES) return ROLE_TYPE
        if (previous >= 0 && kinds[previous] == IDENTIFIER && isWord(previous, "type") &&
            !isMemberAccessBefore(previous)
        ) {
            return ROLE_TYPE
        }
        return ROLE_NONE
    }

    /**
     * An identifier or a whole quoted string before `:` on its line, starting an object line or after `{`, `,` or a
     * decorator.
     */
    private fun isKey(
        i: Int,
        previous: Int,
        next: Int,
        brackets: CharSequence,
        decoratorEnd: Int,
    ): Boolean {
        if (next < 0 || !isChar(next, ':') || lineStarts[next]) return false
        if (brackets.isEmpty() || brackets[brackets.length - 1] != '{') return false
        if (kinds[i] == STRING) {
            val quoted = ends[i] - starts[i] >= 2 && text[starts[i]] == '\'' && text[ends[i] - 1] == '\''
            if (!quoted || (previous >= 0 && kinds[previous] == INTERPOLATION_END)) return false
        }
        return lineStarts[i] || previous < 0 || isChar(previous, '{') || isChar(previous, ',') ||
            kinds[previous] == DECORATOR || previous == decoratorEnd
    }

    /** `.` (but not the last of a spread's `...`), `?.`, `.?` or `::`. */
    private fun isMemberAccess(i: Int): Boolean {
        val length = ends[i] - starts[i]
        val c = text[starts[i]]
        val spread = starts[i] > 0 && text[starts[i] - 1] == '.'
        return when (kinds[i]) {
            PUNCTUATION -> (length == 1 && c == '.' && !spread) || (length == 2 && c == ':')
            OPERATOR -> length == 2 && ((c == '?' && text[starts[i] + 1] == '.') || c == '.')
            else -> false
        }
    }

    /** Whether the significant token before [i] is a member access. */
    private fun isMemberAccessBefore(i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && kinds[j] == COMMENT) j--
        return j >= 0 && isMemberAccess(j)
    }

    /** The next token after [i] that is no comment, or -1. */
    fun nextSignificant(i: Int): Int {
        var j = i + 1
        while (j < size && kinds[j] == COMMENT) j++
        return if (j < size) j else -1
    }

    private fun directlyFollowedBy(
        i: Int,
        c: Char,
    ): Boolean = ends[i] < text.length && text[ends[i]] == c

    companion object {
        const val COMMENT: Byte = 0
        const val STRING: Byte = 1
        const val MULTILINE_STRING: Byte = 2
        const val INTERPOLATION_START: Byte = 3
        const val INTERPOLATION_END: Byte = 4
        const val IDENTIFIER: Byte = 5
        const val NUMBER: Byte = 6
        const val DECORATOR: Byte = 7
        const val OPERATOR: Byte = 8
        const val PUNCTUATION: Byte = 9

        const val ROLE_NONE: Byte = 0
        const val ROLE_MEMBER: Byte = 1
        const val ROLE_KEYWORD: Byte = 2
        const val ROLE_TYPE: Byte = 3
        const val ROLE_BUILTIN: Byte = 4
        const val ROLE_PROPERTY: Byte = 5

        private const val INITIAL_CAPACITY = 64

        val KEYWORDS =
            setOf(
                "targetScope",
                "metadata",
                "param",
                "var",
                "resource",
                "module",
                "output",
                "existing",
                "type",
                "func",
                "import",
                "from",
                "extension",
                "extends",
                "assert",
                "with",
                "as",
                "using",
                "if",
                "for",
                "in",
                "true",
                "false",
                "null",
            )
        private val TYPES = setOf("string", "int", "bool", "object", "array")

        /** Operators of two characters that are styled; `::` is punctuation. */
        private val TWO_CHAR_OPERATORS = setOf("??", "?.", ".?", "==", "!=", "=~", "!~", "&&", "||", "=>")

        /** Tokenizes [text] in one pass. */
        fun lex(text: String): BicepTokens = Lexer(text).run()

        private fun isIdentifierStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'

        private fun isIdentifierPart(c: Char) = isIdentifierStart(c) || c in '0'..'9'
    }

    /**
     * Reads the tokens. Strings with interpolations nest (`'a${f('b${c}')}'`): [interpolations] holds the brace depth
     * of each open `${`, innermost last; a line break ends all of them along with their strings.
     */
    private class Lexer(
        private val text: String,
    ) {
        private val tokens = BicepTokens(text)
        private var position = 0
        private var lineStart = true
        private val interpolations = ArrayList<Int>()

        fun run(): BicepTokens {
            while (position < text.length) {
                val c = text[position]
                when {
                    c == '\n' -> {
                        lineStart = true
                        interpolations.clear()
                        position++
                    }

                    c == ' ' || c == '\t' || c == '\r' -> {
                        position++
                    }

                    c == '/' && next(1) == '/' -> {
                        val end = text.indexOf('\n', position).let { if (it < 0) text.length else it }
                        emit(COMMENT, end)
                    }

                    c == '/' && next(1) == '*' -> {
                        val close = text.indexOf("*/", position + 2)
                        val end = if (close < 0) text.length else close + 2
                        val multiline = (position until end).any { text[it] == '\n' }
                        emit(COMMENT, end)
                        if (multiline) {
                            lineStart = true
                            interpolations.clear()
                        }
                    }

                    c == '\'' && next(1) == '\'' && next(2) == '\'' -> {
                        val close = text.indexOf("'''", position + 3)
                        emit(MULTILINE_STRING, if (close < 0) text.length else close + 3)
                    }

                    c == '\'' -> {
                        string(position)
                    }

                    c == '{' && interpolations.isNotEmpty() -> {
                        interpolations[interpolations.size - 1]++
                        emit(PUNCTUATION, position + 1)
                    }

                    c == '}' && interpolations.isNotEmpty() -> {
                        val depth = interpolations.last()
                        if (depth > 0) {
                            interpolations[interpolations.size - 1] = depth - 1
                            emit(PUNCTUATION, position + 1)
                        } else {
                            interpolations.removeAt(interpolations.size - 1)
                            emit(INTERPOLATION_END, position + 1)
                            string(position - 1)
                        }
                    }

                    isIdentifierStart(c) -> {
                        emit(IDENTIFIER, identifierEnd(position))
                    }

                    c in '0'..'9' -> {
                        var end = position
                        while (end < text.length && text[end] in '0'..'9') end++
                        emit(NUMBER, end)
                    }

                    c == '@' && position + 1 < text.length && isIdentifierStart(text[position + 1]) -> {
                        var end = identifierEnd(position + 1)
                        while (end + 1 < text.length && text[end] == '.' && isIdentifierStart(text[end + 1])) {
                            end = identifierEnd(end + 1)
                        }
                        emit(DECORATOR, end)
                    }

                    else -> {
                        operatorOrPunctuation(c)
                    }
                }
            }
            return tokens
        }

        private fun operatorOrPunctuation(c: Char) {
            val two = if (position + 1 < text.length) text.substring(position, position + 2) else ""
            when {
                two in TWO_CHAR_OPERATORS -> emit(OPERATOR, position + 2)
                two == "::" -> emit(PUNCTUATION, position + 2)
                c == '!' || c == '?' || c == ':' -> emit(OPERATOR, position + 1)
                else -> emit(PUNCTUATION, position + 1)
            }
        }

        /**
         * Reads a `'…'` string from [from], which is its opening quote or the `}` closing an interpolation (then
         * already emitted, so the string goes on after it), up to its closing quote, the next `${` or its line's end.
         */
        private fun string(from: Int) {
            var i = from + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> {
                        i += if (i + 1 < text.length && text[i + 1] != '\n') 2 else 1
                    }

                    '\'' -> {
                        emit(STRING, i + 1)
                        return
                    }

                    '$' -> {
                        if (i + 1 < text.length && text[i + 1] == '{') {
                            if (i > position) emit(STRING, i)
                            emit(INTERPOLATION_START, i + 2)
                            interpolations += 0
                            return
                        }
                        i++
                    }

                    '\n' -> {
                        break
                    }

                    else -> {
                        i++
                    }
                }
            }
            // Unterminated: the string, and any it is interpolated into, ends with the line.
            if (i > position) emit(STRING, i)
            interpolations.clear()
        }

        private fun identifierEnd(from: Int): Int {
            var end = from
            while (end < text.length && isIdentifierPart(text[end])) end++
            return end
        }

        private fun next(offset: Int): Char? = text.getOrNull(position + offset)

        /** Adds a token of [kind] from the current position to [end] and moves there. */
        private fun emit(
            kind: Byte,
            end: Int,
        ) {
            tokens.add(kind, position, end, lineStart)
            if (kind != COMMENT) lineStart = false
            position = end
        }
    }
}
