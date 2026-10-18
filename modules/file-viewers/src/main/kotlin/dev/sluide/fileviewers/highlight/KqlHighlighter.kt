package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle

/**
 * Highlights the Kusto Query Language (KQL), which has no RSyntaxTextArea lexer, in one linear pass.
 *
 * Tabular operators (`where`, `summarize`, …) are keywords after `|`. Where a statement begins (at the start of the
 * text, after `;`, `<|`, `{`, a `(` that is no call, `let name =`, and after a blank line, since editors run the
 * query blocks between blank lines on their own), only the operators a query can begin with ([SOURCES]: `print`,
 * `range`, `union`, …) and statements like `set` are, and only when no `)`, `,`, `.` or comparison follows them on
 * their line (`(range > 3)` is a column) and, except for `datatable` and `externaldata`, no `(` directly
 * (`range(1, 5, 1)` is a call). Elsewhere `count` or `sample` are columns, or functions when directly followed by `(`.
 * Hyphenated operators (`project-away`, `mv-expand`) are keywords anywhere, since `a-b` with names like these is no
 * plausible arithmetic; other `a-b` stays plain. `from`, `to`, `step` and `default` are keywords within `range` and
 * `make-series`, outside the parentheses opened after them. Management commands (`.show`, `.create-or-alter`) are
 * annotations where a statement begins, or first on a line after a `}` (a function body). Nothing is ever flagged as
 * an error: an unterminated string runs to the end of its line, a ```` ``` ```` string to the end of the text.
 */
internal object KqlHighlighter : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> = KqlScanner(text).scan()
}

private fun words(words: String): Set<String> = words.trim().split(Regex("""\s+""")).toSet()

/** Tabular operators after a pipe. */
private val TABULAR =
    words(
        """
        as consume count datatable distinct evaluate extend externaldata facet filter find fork getschema invoke join
        limit lookup makeseries mvexpand order parse partition print project range reduce render sample scan search
        serialize sort summarize take top union where
        """,
    )

/** Operators that begin a query, and statements, where a statement begins. */
private val SOURCES =
    words("datatable evaluate externaldata find print range search union alias declare pattern restrict set")

/** Operators with hyphens, which are one keyword. */
private val HYPHENATED =
    words(
        """
        project-away project-keep project-rename project-reorder project-smart mv-expand mv-apply parse-where parse-kv
        make-series make-graph top-nested top-hitters sample-distinct graph-match graph-merge graph-to-table
        graph-shortest-paths graph-mark-components macro-expand execute-show-command
        """,
    )

/** Query-start operators whose `(` is a schema, not a call. */
private val SCHEMA_SOURCES = words("datatable externaldata")

/** Characters after a query-start operator that make it a column: `(range > 3)`, `print, x`. */
private const val AFTER_COLUMN = "),;=<>!/%.]|"

/** Operators within which [RANGE_KEYWORDS] are keywords, and those keywords. */
private val RANGE_OPERATORS = words("range make-series makeseries")

private val RANGE_KEYWORDS = words("from to step default")

/** Every hyphenated operator name up to one of its hyphens, so that `graph-to-table` is read part by part. */
private val HYPHENATED_PREFIXES =
    HYPHENATED.flatMap { name -> name.indices.filter { name[it] == '-' }.map { name.substring(0, it) } }.toSet()

private val KEYWORDS =
    words("let by on kind with and or not in between asc desc nulls first last true false view of")

/** String operators; those in [NEGATABLE] also with a leading `!`. */
private val STRING_OPERATORS =
    words(
        """
        has has_cs has_any has_all hasprefix hasprefix_cs hassuffix hassuffix_cs contains contains_cs startswith
        startswith_cs endswith endswith_cs matches regex
        """,
    )

private val NEGATABLE =
    words(
        """
        has has_cs hasprefix hasprefix_cs hassuffix hassuffix_cs contains contains_cs startswith startswith_cs
        endswith endswith_cs in between
        """,
    )

private val TYPES =
    words("string long int real double bool boolean datetime date timespan time dynamic guid uniqueid decimal")

/** Literals whose content in parentheses is no expression but the value itself: `datetime(2024-01-31)`. */
private val RAW_LITERALS = words("datetime date timespan time guid uniqueid")

/** Timespan units after a number (`5m`, `2hrs`, `1.5d`), lower case. */
private val TIMESPAN_UNITS =
    words(
        """
        d day days h hr hrs hour hours m min minute minutes s sec second seconds ms milli millis millisec millisecond
        milliseconds micro micros microsec microsecond microseconds tick ticks
        """,
    )

/** Two-character operators that are styled; `!=` and `!~` are found by `negation`. */
private val OPERATORS = words("<| == =~ <> <= >=")

/** One pass over [text]; not reused, so [KqlHighlighter] stays stateless. */
private class KqlScanner(
    private val text: String,
) {
    private enum class Kind { WORD, PUNCTUATION, LITERAL }

    /** A token that is no comment, for deciding what may follow it; [end] is exclusive. */
    private class Token(
        val kind: Kind,
        val text: String,
        val end: Int,
    )

    private val spans = ArrayList<HighlightSpan>()
    private var position = 0
    private var previous: Token? = null
    private var beforePrevious: Token? = null
    private var thirdLast: Token? = null

    /** A blank line came after the last token. */
    private var blankLine = false

    /** The current line has a token or comment. */
    private var lineHasToken = false

    /** How many brackets are open: `(`, `[` and `{` less their closers. */
    private var depth = 0

    /** The [depth] of the `range` or `make-series` being read, `null` outside them. */
    private var rangeDepth: Int? = null

    fun scan(): List<HighlightSpan> {
        while (position < text.length) {
            val c = text[position]
            when {
                c == '\n' -> {
                    if (!lineHasToken) {
                        blankLine = true
                        rangeDepth = null
                    }
                    lineHasToken = false
                    position++
                }

                c.isWhitespace() -> {
                    position++
                }

                c == '/' && at(position + 1) == '/' -> {
                    comment()
                }

                text.startsWith("```", position) -> {
                    multiLineString(position, position + 3)
                }

                c == '\'' || c == '"' -> {
                    quoted(position, position, escapes = true)
                }

                c == '@' && isQuote(at(position + 1)) -> {
                    quoted(position, position + 1, escapes = false)
                }

                (c == 'h' || c == 'H') && obfuscated() -> {}

                c.isDigit() -> {
                    number()
                }

                c == '.' && isIdentifierStart(at(position + 1)) && commandBegins() -> {
                    command()
                }

                isIdentifierStart(c) -> {
                    word()
                }

                c == '!' -> {
                    negation()
                }

                else -> {
                    punctuation(c)
                }
            }
        }
        return spans
    }

    private fun at(index: Int): Char = if (index < text.length) text[index] else '\u0000'

    private fun isLineEnd(c: Char) = c == '\n' || c == '\r'

    private fun isQuote(c: Char) = c == '\'' || c == '"'

    private fun isIdentifierStart(c: Char) = c.isLetter() || c == '_' || c == '$'

    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun identifierEnd(from: Int): Int {
        var end = from
        while (end < text.length && isIdentifierPart(text[end])) end++
        return end
    }

    private fun span(
        start: Int,
        end: Int,
        style: TokenStyle,
    ) {
        if (end > start) spans += HighlightSpan(start, end, style)
    }

    private fun token(
        kind: Kind,
        tokenText: String,
        end: Int,
    ) {
        thirdLast = beforePrevious
        beforePrevious = previous
        previous = Token(kind, tokenText, end)
        blankLine = false
        lineHasToken = true
        position = end
    }

    private fun afterPipe(): Boolean = previous?.let { it.kind == Kind.PUNCTUATION && it.text == "|" } == true

    /** Whether a statement, query or management command may begin at the current token. */
    private fun statementBegins(): Boolean {
        val last = previous ?: return true
        if (blankLine) return true
        return when (last.text) {
            "<|", ";", "{" -> {
                last.kind == Kind.PUNCTUATION
            }

            "(" -> {
                !isCall(last)
            }

            "=" -> {
                beforePrevious?.kind == Kind.WORD && thirdLast?.let { it.kind == Kind.WORD && it.text == "let" } == true
            }

            else -> {
                false
            }
        }
    }

    /**
     * Where a statement begins, or first on a line after a `}` that ends a function body (`.create function … { … }`
     * and the next command). A `.name` first on other lines continues an expression.
     */
    private fun commandBegins(): Boolean =
        statementBegins() || (!lineHasToken && previous?.let { it.kind == Kind.PUNCTUATION && it.text == "}" } == true)

    /** Whether the query-start operator [name], ending at [end], is one rather than a column or a call. */
    private fun startsQuery(
        name: String,
        end: Int,
    ): Boolean {
        if (at(end) == '(') return name in SCHEMA_SOURCES
        var next = end
        while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
        return next == text.length || text[next] !in AFTER_COLUMN
    }

    /** A `(` directly after a name: `bin(`. */
    private fun isCall(paren: Token): Boolean {
        val before = beforePrevious ?: return false
        return before.kind == Kind.WORD && before.end == paren.end - 1
    }

    private fun comment() {
        var end = position
        while (end < text.length && !isLineEnd(text[end])) end++
        span(position, end, TokenStyle.COMMENT)
        lineHasToken = true
        position = end
    }

    /** ```` ```…``` ````, from [start] with the content at [contentStart], to the end of the text if unterminated. */
    private fun multiLineString(
        start: Int,
        contentStart: Int,
    ) {
        val close = text.indexOf("```", contentStart)
        val end = if (close < 0) text.length else close + 3
        span(start, end, TokenStyle.STRING)
        token(Kind.LITERAL, "", end)
    }

    /**
     * A string from [start] whose quote is at [quote]: with `\` escapes, or verbatim (`@'C:\x'`, a doubled quote
     * standing for one). Unterminated, it ends with its line.
     */
    private fun quoted(
        start: Int,
        quote: Int,
        escapes: Boolean,
    ) {
        val q = text[quote]
        var end = quote + 1
        while (end < text.length) {
            val c = text[end]
            when {
                isLineEnd(c) -> {
                    break
                }

                escapes && c == '\\' && end + 1 < text.length && !isLineEnd(text[end + 1]) -> {
                    end += 2
                }

                c == q && !escapes && at(end + 1) == q -> {
                    end += 2
                }

                c == q -> {
                    end++
                    break
                }

                else -> {
                    end++
                }
            }
        }
        span(start, end, TokenStyle.STRING)
        token(Kind.LITERAL, "", end)
    }

    /** Obfuscated strings: `h'…'`, `h"…"`, `h@'…'`, `h```…````. */
    private fun obfuscated(): Boolean {
        val next = at(position + 1)
        when {
            isQuote(next) -> quoted(position, position + 1, escapes = true)
            next == '@' && isQuote(at(position + 2)) -> quoted(position, position + 2, escapes = false)
            text.startsWith("```", position + 1) -> multiLineString(position, position + 4)
            else -> return false
        }
        return true
    }

    /** Integers, reals with exponent, hex, and timespans (`5m`, `1.5d`); a number with any other suffix is plain. */
    private fun number() {
        val start = position
        var end = start
        if (text[start] == '0' && (at(start + 1) == 'x' || at(start + 1) == 'X') && at(start + 2).isHexDigit()) {
            end = start + 2
            while (end < text.length && text[end].isHexDigit()) end++
        } else {
            while (end < text.length && text[end].isDigit()) end++
            if (at(end) == '.' && at(end + 1).isDigit()) {
                end++
                while (end < text.length && text[end].isDigit()) end++
            }
            if (at(end) == 'e' || at(end) == 'E') {
                val sign = if (at(end + 1) == '+' || at(end + 1) == '-') 1 else 0
                if (at(end + 1 + sign).isDigit()) {
                    end += 1 + sign
                    while (end < text.length && text[end].isDigit()) end++
                }
            }
        }
        val suffixEnd = identifierEnd(end)
        val suffix = text.substring(end, suffixEnd)
        if (suffix.isEmpty() || suffix.lowercase() in TIMESPAN_UNITS) {
            span(start, suffixEnd, TokenStyle.NUMBER)
        }
        token(Kind.LITERAL, "", suffixEnd)
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** `.show`, `.create-or-alter`. */
    private fun command() {
        var end = identifierEnd(position + 1)
        while (at(end) == '-' && isIdentifierStart(at(end + 1))) end = identifierEnd(end + 1)
        span(position, end, TokenStyle.ANNOTATION)
        token(Kind.WORD, text.substring(position, end), end)
    }

    private fun word() {
        val start = position
        val end = identifierEnd(start + 1)
        val name = text.substring(start, end)
        if (at(end) == '-' && name in HYPHENATED_PREFIXES && hyphenated(start, name, end)) return
        if (name == "in" && at(end) == '~') {
            span(start, end + 1, TokenStyle.KEYWORD)
            token(Kind.WORD, "in~", end + 1)
            return
        }
        val call = at(end) == '('
        val style =
            when {
                name in TABULAR && afterPipe() -> {
                    TokenStyle.KEYWORD
                }

                name in SOURCES && statementBegins() && startsQuery(name, end) -> {
                    TokenStyle.KEYWORD
                }

                name in RANGE_KEYWORDS && rangeDepth == depth -> {
                    TokenStyle.KEYWORD
                }

                name in KEYWORDS || name in STRING_OPERATORS -> {
                    TokenStyle.KEYWORD
                }

                name in TYPES && (call || declaredType()) -> {
                    TokenStyle.TYPE
                }

                call -> {
                    TokenStyle.BUILTIN
                }

                else -> {
                    null
                }
            }
        if (style != null) span(start, end, style)
        if (style == TokenStyle.KEYWORD && name in RANGE_OPERATORS) rangeDepth = depth
        token(Kind.WORD, name, end)
        if (style == TokenStyle.TYPE && name in RAW_LITERALS) rawLiteral()
    }

    /** A type after `:` (`x: long`) or inside `typeof(…)`. */
    private fun declaredType(): Boolean {
        val last = previous ?: return false
        if (last.kind != Kind.PUNCTUATION) return false
        return last.text == ":" || (last.text == "(" && isCall(last) && beforePrevious?.text == "typeof")
    }

    /**
     * The longest hyphenated operator at [start], where [name] (ending at [end]) is its first part, as a keyword;
     * `false` if there is none.
     */
    private fun hyphenated(
        start: Int,
        name: String,
        end: Int,
    ): Boolean {
        var candidate = name
        var candidateEnd = end
        var bestEnd = -1
        while (at(candidateEnd) == '-' && isIdentifierStart(at(candidateEnd + 1))) {
            val partEnd = identifierEnd(candidateEnd + 1)
            candidate = text.substring(start, partEnd)
            candidateEnd = partEnd
            if (candidate in HYPHENATED) bestEnd = partEnd
            if (candidate !in HYPHENATED_PREFIXES) break
        }
        if (bestEnd < 0) return false
        span(start, bestEnd, TokenStyle.KEYWORD)
        val operator = text.substring(start, bestEnd)
        if (operator in RANGE_OPERATORS) rangeDepth = depth
        token(Kind.WORD, operator, bestEnd)
        return true
    }

    /** The value of `datetime(2024-01-31 10:00)` or `guid(…)` as a number, when it is on one line and unquoted. */
    private fun rawLiteral() {
        if (at(position) != '(') return
        var close = position + 1
        while (close < text.length && text[close] !in "()'\"`\n\r") close++
        if (at(close) != ')') return
        val content = text.substring(position + 1, close)
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed == "null") return
        val contentStart = position + 1 + (content.length - content.trimStart().length)
        span(contentStart, contentStart + trimmed.length, TokenStyle.NUMBER)
        token(Kind.LITERAL, "", close + 1)
    }

    /** `!has`, `!in~` as one keyword; `!=` and `!~` as operators. */
    private fun negation() {
        val start = position
        if (isIdentifierStart(at(start + 1))) {
            val end = identifierEnd(start + 2)
            val name = text.substring(start + 1, end)
            if (name in NEGATABLE) {
                val tilde = if (name == "in" && at(end) == '~') 1 else 0
                span(start, end + tilde, TokenStyle.KEYWORD)
                token(Kind.WORD, text.substring(start, end + tilde), end + tilde)
                return
            }
        }
        if (at(start + 1) == '=' || at(start + 1) == '~') {
            span(start, start + 2, TokenStyle.OPERATOR)
            token(Kind.PUNCTUATION, text.substring(start, start + 2), start + 2)
        } else {
            token(Kind.PUNCTUATION, "!", start + 1)
        }
    }

    private fun punctuation(c: Char) {
        val start = position
        val two = if (start + 1 < text.length) text.substring(start, start + 2) else ""
        when {
            two in OPERATORS -> {
                if (two == "<|") rangeDepth = null
                span(start, start + 2, TokenStyle.OPERATOR)
                token(Kind.PUNCTUATION, two, start + 2)
            }

            c == '|' || c == '<' || c == '>' -> {
                if (c == '|') rangeDepth = null
                span(start, start + 1, TokenStyle.OPERATOR)
                token(Kind.PUNCTUATION, c.toString(), start + 1)
            }

            else -> {
                when (c) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ';' -> rangeDepth = null
                }
                token(Kind.PUNCTUATION, c.toString(), start + 1)
            }
        }
    }
}
