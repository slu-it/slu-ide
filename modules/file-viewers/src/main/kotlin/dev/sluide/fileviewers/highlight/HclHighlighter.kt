package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle

/**
 * Highlights HCL (Terraform `.tf` and `.tfvars`, `.hcl`, Packer `.pkr.hcl`, Nomad), which has no RSyntaxTextArea
 * lexer: our own tokenizer, one linear pass with a stack of open brackets and template interpolations.
 *
 * Styles: `#`, `//` and block comments; the first word of a block header (`resource "a" "b" {`, `lifecycle {`) as
 * `keyword` and its labels (quoted or bare) as `type`; attribute names before `=` and object keys (`a = 1`,
 * `"b" = 2`, `c: 3`) as `property`; strings with `\` escapes and heredocs (`<<EOF`, `<<-EOF`, up to the line that
 * is only the marker), whose `${…}` and `%{…}` are code between `${`/`%{` (with a `~`) and `}` operators, nested;
 * `$${` and `%%{` are escapes; numbers; `true`, `false`, `null`; `for`, `in`, `if` inside `[…]`, `{…}` and template
 * sequences and `else`, `endfor`, `endif` inside `%{…}` as `keyword`; a word directly followed by `(` (also
 * `provider::ns::fn`) as `builtin`; the roots `var`, `local`, `module`, `data`, `each`, `count`, `self`, `path`,
 * `terraform` before a `.` as `builtin`; the operators `== != < > <= >= && || ! + - * / % ? : => ...`. Words after
 * `.` are members and never keywords. Identifiers may contain `-`, as in HCL.
 *
 * Nothing is flagged as an error: an unterminated string ends with its line, a heredoc, block comment or `${` with
 * the text. A typo does not restyle the rest of the file: at the start of a line a block header (an identifier,
 * labels and `{`) ends every open bracket, object and interpolation up to the enclosing block (blocks cannot sit
 * in expressions), and an `identifier =` line ends open `(`, `[` and the `${` of quoted strings. An unclosed block is not ended: it only
 * changes where a stray `}` lands, not any style.
 */
internal object HclHighlighter : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> = HclScanner(text).run()
}

private const val PAREN = 0
private const val BRACKET = 1
private const val BLOCK = 2
private const val OBJECT = 3
private const val INTERP = 4

/** An open bracket; an [INTERP] also holds the string it interrupts ([marker] of a heredoc) and the outer counts. */
private class Frame(
    val kind: Int,
    val marker: String? = null,
    val directive: Boolean = false,
    val saved: IntArray = IntArray(0),
)

private val LITERALS = setOf("true", "false", "null")
private val ROOTS = setOf("var", "local", "module", "data", "each", "count", "self", "path", "terraform")
private val EXPRESSION_WORDS = setOf("for", "in", "if")
private val DIRECTIVE_WORDS = setOf("for", "in", "if", "else", "endfor", "endif")
private val TWO_CHAR_OPERATORS = setOf("==", "!=", "<=", ">=", "&&", "||", "=>")

private fun isIdentifierStart(c: Char) = c == '_' || c.isLetter()

private fun isIdentifierPart(c: Char) = c == '_' || c == '-' || c.isLetterOrDigit()

private class HclScanner(
    private val text: String,
) {
    private val n = text.length
    private val spans = ArrayList<HighlightSpan>()
    private var pos = 0
    private val stack = ArrayList<Frame>()

    /** The open `(`, `[` and `{` since the innermost interpolation, so that closers never leave it. */
    private var counts = IntArray(3)
    private var interpolations = 0
    private var lineStart = true
    private var lineChecked = false
    private var firstOnLine = false
    private var blockBrace = false
    private var last = '\u0000'
    private var afterDot = false

    fun run(): List<HighlightSpan> {
        while (pos < n) {
            val c = text[pos]
            when {
                c == '\n' -> {
                    lineStart = true
                    lineChecked = false
                    pos++
                }

                c == ' ' || c == '\t' || c == '\r' -> {
                    pos++
                }

                else -> {
                    if (lineStart && !lineChecked) {
                        lineChecked = true
                        if (atLineStart()) continue
                    }
                    token(c)
                }
            }
        }
        return spans
    }

    private fun token(c: Char) {
        val next = text.getOrNull(pos + 1)
        if (c == '#' || (c == '/' && next == '/')) {
            val end = text.indexOf('\n', pos).let { if (it < 0) n else it }
            add(pos, end, TokenStyle.COMMENT)
            pos = end
            return
        }
        if (c == '/' && next == '*') {
            blockComment()
            return
        }
        firstOnLine = lineStart
        lineStart = false
        when {
            c == '"' -> {
                templateScan(pos, pos + 1, null, true)
            }

            c == '<' && next == '<' && heredoc() -> {}

            isIdentifierStart(c) -> {
                identifier()
            }

            c in '0'..'9' -> {
                number()
            }

            else -> {
                operatorOrPunctuation(c, next)
            }
        }
    }

    private fun blockComment() {
        val close = text.indexOf("*/", pos + 2)
        val end = if (close < 0) n else close + 2
        add(pos, end, TokenStyle.COMMENT)
        if ((pos until end).any { text[it] == '\n' }) {
            lineStart = true
            lineChecked = false
        }
        pos = end
    }

    // Line starts: block headers and attribute lines decide what ends open constructs.

    /** Applies the reset rules at the first token of a line; true if it styled a block header and moved on. */
    private fun atLineStart(): Boolean {
        if (!isIdentifierStart(text[pos])) return false
        val brace = scanHeader(pos, false)
        if (brace >= 0) {
            while (stack.isNotEmpty() && stack.last().kind != BLOCK) pop()
            scanHeader(pos, true)
            pos = brace
            blockBrace = true
            lineStart = false
            last = '"'
            afterDot = false
            return true
        }
        if (stack.isNotEmpty() && isUnfinished(stack.last()) && equalsFollows(identifierEnd(pos, false))) {
            while (stack.isNotEmpty() && isUnfinished(stack.last())) pop()
        }
        return false
    }

    /** An open `(` or `[`, or `${` of a quoted string (heredocs go on over lines): attributes cannot be inside. */
    private fun isUnfinished(frame: Frame) = frame.kind <= BRACKET || (frame.kind == INTERP && frame.marker == null)

    /**
     * The position of the `{` that ends a block header at [from] (identifier, then identifier or plain quoted labels
     * separated by blanks), or -1. With [emit] it styles the type as keyword and the labels as type.
     */
    private fun scanHeader(
        from: Int,
        emit: Boolean,
    ): Int {
        var i = identifierEnd(from, false)
        if (emit) add(from, i, TokenStyle.KEYWORD)
        while (true) {
            val j = skipBlanks(i)
            if (j >= n) return -1
            val c = text[j]
            if (c == '{') return j
            if (j == i) return -1
            val end =
                when {
                    c == '"' -> plainStringEnd(j)
                    isIdentifierStart(c) -> identifierEnd(j, false)
                    else -> -1
                }
            if (end < 0) return -1
            if (emit) add(j, end, TokenStyle.TYPE)
            i = end
        }
    }

    /** The end of the string at [from] without interpolations that ends on its line, or -1. */
    private fun plainStringEnd(from: Int): Int {
        var i = from + 1
        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> return i + 1
                c == '\n' -> return -1
                c == '\\' -> i += 2
                (c == '$' || c == '%') && text.getOrNull(i + 1) == '{' -> return -1
                else -> i++
            }
        }
        return -1
    }

    // Strings, templates and heredocs.

    /**
     * Scans the template text from [from] (a string's start from [chunkStart], or where an interpolation ended) up to
     * the closing quote or marker line, the next interpolation, the line's end (strings) or the text's end.
     */
    private fun templateScan(
        chunkStart: Int,
        from: Int,
        marker: String?,
        fresh: Boolean,
    ) {
        var i = from
        while (i < n) {
            val c = text[i]
            if (marker != null && (i == 0 || text[i - 1] == '\n')) {
                val end = closingMarkerEnd(i, marker)
                if (end >= 0) {
                    finishString(chunkStart, end, TokenStyle.STRING)
                    return
                }
            }
            when {
                c == '\\' && marker == null -> {
                    i += if (i + 1 < n && text[i + 1] != '\n') 2 else 1
                }

                (c == '$' || c == '%') && i + 1 < n && text[i + 1] == c && text.getOrNull(i + 2) == '{' -> {
                    i += 3
                }

                (c == '$' || c == '%') && i + 1 < n && text[i + 1] == '{' -> {
                    openInterpolation(chunkStart, i, c == '%', marker)
                    return
                }

                c == '"' && marker == null -> {
                    val key = fresh && chunkStart == from - 1 && keyFollows(i + 1)
                    finishString(chunkStart, i + 1, if (key) TokenStyle.PROPERTY else TokenStyle.STRING)
                    return
                }

                c == '\n' && marker == null -> {
                    finishString(chunkStart, i, TokenStyle.STRING)
                    return
                }

                else -> {
                    i++
                }
            }
        }
        finishString(chunkStart, n, TokenStyle.STRING)
    }

    private fun finishString(
        start: Int,
        end: Int,
        style: TokenStyle,
    ) {
        add(start, end, style)
        pos = end
        last = '"'
        afterDot = false
    }

    private fun openInterpolation(
        chunkStart: Int,
        at: Int,
        directive: Boolean,
        marker: String?,
    ) {
        add(chunkStart, at, TokenStyle.STRING)
        val end = at + if (text.getOrNull(at + 2) == '~') 3 else 2
        add(at, end, TokenStyle.OPERATOR)
        stack += Frame(INTERP, marker, directive, counts)
        counts = IntArray(3)
        interpolations++
        pos = end
        last = '{'
        afterDot = false
    }

    /** The end of the line at [from] if it holds only [marker] (and blanks), else -1. */
    private fun closingMarkerEnd(
        from: Int,
        marker: String,
    ): Int {
        val lineEnd = text.indexOf('\n', from).let { if (it < 0) n else it }
        val start = skipBlanks(from).coerceAtMost(lineEnd)
        var end = lineEnd
        while (end > start && (text[end - 1] == ' ' || text[end - 1] == '\t' || text[end - 1] == '\r')) end--
        return if (end - start == marker.length && text.startsWith(marker, start)) end else -1
    }

    /** A heredoc opener `<<EOF` or `<<-EOF` at [pos]: its body is a string up to the marker line. */
    private fun heredoc(): Boolean {
        var j = pos + 2
        if (j < n && text[j] == '-') j++
        if (j >= n || !isIdentifierStart(text[j])) return false
        val markerEnd = identifierEnd(j, false)
        val lineEnd = text.indexOf('\n', markerEnd)
        if (lineEnd < 0) {
            finishString(pos, n, TokenStyle.STRING)
        } else {
            templateScan(pos, lineEnd + 1, text.substring(j, markerEnd), false)
        }
        return true
    }

    // Words and numbers.

    private fun identifier() {
        val start = pos
        val end = identifierEnd(start, true)
        val word = text.substring(start, end)
        val next = text.getOrNull(end)
        var style: TokenStyle? = null
        if (!afterDot) {
            val top = stack.lastOrNull()?.kind
            style =
                when {
                    keyFollows(end) -> TokenStyle.PROPERTY
                    next == '(' -> TokenStyle.BUILTIN
                    word in LITERALS -> TokenStyle.KEYWORD
                    word in ROOTS && next == '.' && text.getOrNull(end + 1) != '.' -> TokenStyle.BUILTIN
                    word in EXPRESSION_WORDS && (top == BRACKET || top == OBJECT || top == INTERP) -> TokenStyle.KEYWORD
                    word in DIRECTIVE_WORDS && top == INTERP && stack.last().directive -> TokenStyle.KEYWORD
                    else -> null
                }
        }
        if (style != null) add(start, end, style)
        pos = end
        last = text[end - 1]
        afterDot = false
    }

    private fun number() {
        var end = pos
        while (end < n && text[end] in '0'..'9') end++
        if (!afterDot) {
            if (end + 1 < n && text[end] == '.' && text[end + 1] in '0'..'9') {
                end++
                while (end < n && text[end] in '0'..'9') end++
            }
            if (end < n && (text[end] == 'e' || text[end] == 'E')) {
                var k = end + 1
                if (k < n && (text[k] == '+' || text[k] == '-')) k++
                if (k < n && text[k] in '0'..'9') {
                    while (k < n && text[k] in '0'..'9') k++
                    end = k
                }
            }
        }
        add(pos, end, TokenStyle.NUMBER)
        pos = end
        last = '0'
        afterDot = false
    }

    private fun identifierEnd(
        from: Int,
        scoped: Boolean,
    ): Int {
        var end = from
        while (true) {
            while (end < n && isIdentifierPart(text[end])) end++
            val more = scoped && text.startsWith("::", end) && end + 2 < n && isIdentifierStart(text[end + 2])
            if (!more) return end
            end += 2
        }
    }

    // Keys: names before `=`, and before `:` in objects.

    /** Whether the name that ended before [from] is an attribute name or object key. */
    private fun keyFollows(from: Int): Boolean {
        val j = skipBlanks(from)
        if (j >= n) return false
        val next = text.getOrNull(j + 1)
        return when (text[j]) {
            '=' -> {
                next != '=' && next != '>' && keyContext()
            }

            ':' -> {
                next != ':' && stack.lastOrNull()?.kind == OBJECT &&
                    (last == '{' || last == ',' || (firstOnLine && last != '?'))
            }

            else -> {
                false
            }
        }
    }

    /** Attributes sit in blocks, objects and at the top; not in brackets, calls or interpolations. */
    private fun keyContext(): Boolean {
        val top = stack.lastOrNull()?.kind
        return top == null || top == BLOCK || top == OBJECT
    }

    private fun equalsFollows(from: Int): Boolean {
        val j = skipBlanks(from)
        return j < n && text[j] == '=' && text.getOrNull(j + 1) != '=' && text.getOrNull(j + 1) != '>'
    }

    // Operators and brackets.

    private fun operatorOrPunctuation(
        c: Char,
        next: Char?,
    ) {
        val two = if (next != null) "$c$next" else ""
        when {
            c == '.' && next == '.' && text.getOrNull(pos + 2) == '.' -> emit(3, TokenStyle.OPERATOR, '.')
            c == '.' -> emit(1, null, '.', true)
            two in TWO_CHAR_OPERATORS -> emit(2, TokenStyle.OPERATOR, next ?: c)
            c in "!+-*/%?:<>" -> emit(1, TokenStyle.OPERATOR, c)
            c == '(' -> open(PAREN, 0)
            c == '[' -> open(BRACKET, 1)
            c == '{' -> open(if (blockBrace) BLOCK else OBJECT, 2)
            c == ')' -> close(PAREN, 0)
            c == ']' -> close(BRACKET, 1)
            c == '}' -> closeBrace(1)
            c == '~' && next == '}' && counts[2] == 0 && interpolations > 0 -> closeBrace(2)
            else -> emit(1, null, c)
        }
    }

    private fun emit(
        length: Int,
        style: TokenStyle?,
        lastChar: Char,
        dot: Boolean = false,
    ) {
        if (style != null) add(pos, pos + length, style)
        pos += length
        last = lastChar
        afterDot = dot
    }

    private fun open(
        kind: Int,
        count: Int,
    ) {
        stack += Frame(kind)
        counts[count]++
        blockBrace = false
        emit(1, null, text[pos])
    }

    private fun close(
        kind: Int,
        count: Int,
    ) {
        if (counts[count] > 0) {
            while (pop().kind != kind) continue
        }
        emit(1, null, text[pos])
    }

    /** A `}` (or `~}`): closes the innermost object or block, else the open interpolation, which resumes its string. */
    private fun closeBrace(length: Int) {
        if (counts[2] > 0) {
            while (pop().kind.let { it != BLOCK && it != OBJECT }) continue
            emit(length, null, '}')
        } else if (interpolations > 0) {
            while (stack.last().kind != INTERP) pop()
            val frame = pop()
            emit(length, TokenStyle.OPERATOR, '}')
            templateScan(pos, pos, frame.marker, false)
        } else {
            emit(length, null, '}')
        }
    }

    private fun pop(): Frame {
        val frame = stack.removeAt(stack.size - 1)
        when (frame.kind) {
            PAREN -> {
                counts[0]--
            }

            BRACKET -> {
                counts[1]--
            }

            INTERP -> {
                counts = frame.saved
                interpolations--
            }

            else -> {
                counts[2]--
            }
        }
        return frame
    }

    private fun skipBlanks(from: Int): Int {
        var i = from
        while (i < n && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    private fun add(
        start: Int,
        end: Int,
        style: TokenStyle,
    ) {
        if (end > start) spans += HighlightSpan(start, end, style)
    }
}
