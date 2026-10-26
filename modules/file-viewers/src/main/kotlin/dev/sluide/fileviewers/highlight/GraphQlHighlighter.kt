package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle

/**
 * Highlights GraphQL (schemas, operations and fragments), which has no RSyntaxTextArea lexer, in one linear pass.
 *
 * - `#` comments are `comment`; `"…"` strings (escapes, ending with their line when unterminated) and `"""…"""`
 *   block strings (also descriptions, ending with the text when unterminated) are `string`.
 * - Words are keywords only where the grammar allows them: the definition keywords ([DEFINITIONS]) where a definition
 *   begins, `implements` and `repeatable` in a definition head, `on` in `fragment F on T`, `... on T` and
 *   `directive @d on LOCATION`, `true`, `false` and `null` as values. Elsewhere `type`, `query`, `input` or `on` are
 *   ordinary names.
 * - `type`: the name after `type`, `interface`, `union`, `enum`, `input`, `scalar`; every name in a type position
 *   (after `:` in a definition, a variable definition or definition arguments, up to the `=` of a default value;
 *   after `on`; after `implements`, `&`, and the `=` and `|` of a union), so also `Int`, `ID` and the names in
 *   `[Type!]!`; directive locations (`FIELD_DEFINITION | OBJECT`).
 * - `property`: names before `:` (arguments, input and object fields, aliases) and every name in a selection set,
 *   so an alias and the field it selects are both properties; names before `(` in a definition. A fragment spread
 *   `...Name`, operation, fragment and enum value names stay plain, as do enum values in argument values.
 * - `annotation`: `@directive` and `$variable`, each with its sign. `number`: integers, floats, exponents.
 *   `operator`: `...`, `!`, `|`, `&`, `=`.
 *
 * Recovery from typos: brackets are tracked in a stack. A definition keyword at column 0, directly followed on its
 * line by a name, `{` or `@` (so `type: String` stays a field), clears the stack and every context and starts a new
 * definition, as does an indented one where a definition may begin (the start of the text, or after a definition
 * ended with its `}`). A `}` closes everything up to the nearest brace; a stray `)` or `]` is ignored. Nothing is
 * ever flagged as an error. Known gaps: a keyword that is indented after a definition without braces (`scalar A`
 * then ` type B`) is a name; a field named like a definition keyword at column 0 is read as the start of a
 * definition when a name, `{` or `@` follows it.
 */
internal object GraphQlHighlighter : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> = GraphQlScanner(text).scan()
}

/** What the definition being read is, for the grammar that applies to its brackets. */
private enum class Definition { NONE, OPERATION, FRAGMENT, TYPE, ENUM, UNION, SCALAR, SCHEMA, DIRECTIVE }

private val DEFINITIONS =
    mapOf(
        "query" to Definition.OPERATION,
        "mutation" to Definition.OPERATION,
        "subscription" to Definition.OPERATION,
        "fragment" to Definition.FRAGMENT,
        "type" to Definition.TYPE,
        "interface" to Definition.TYPE,
        "input" to Definition.TYPE,
        "enum" to Definition.ENUM,
        "union" to Definition.UNION,
        "scalar" to Definition.SCALAR,
        "schema" to Definition.SCHEMA,
        "directive" to Definition.DIRECTIVE,
        "extend" to Definition.NONE,
    )

/** Definition keywords that are followed by the name of what they define, a type. */
private val NAMED = setOf("type", "interface", "input", "enum", "union", "scalar")

private val VALUES = setOf("true", "false", "null")

/** Bracket kinds on the stack: selection or definition body, object value, list, value arguments, type arguments. */
private const val BODY = '{'
private const val OBJECT = 'o'
private const val LIST = '['
private const val ARGUMENTS = '('
private const val DEFINED = 't'

/** What the last token was, for the few decisions that look back; `:`, `=` and `.` (for `...`) stand for themselves. */
private const val NAME = 'n'
private const val DIRECTIVE_NAME = '@'
private const val OTHER = ' '

/** One pass over [text]; not reused, so [GraphQlHighlighter] stays stateless. */
private class GraphQlScanner(
    private val text: String,
) {
    private val spans = ArrayList<HighlightSpan>()
    private var position = 0
    private val stack = StringBuilder()

    /** How many of [stack] are braces (`BODY` or `OBJECT`). */
    private var braces = 0
    private var definition = Definition.NONE
    private var definitionMayBegin = true

    /** The next name is the one a definition keyword names. */
    private var expectName = false

    /** Names are types: after `:` or `on`, up to `=`, `{`, `(`, `)`, `}` or `@`. */
    private var typeMode = false

    /** Names outside brackets are types: after `implements`, a union's `=`, directive locations. */
    private var typeList = false
    private var previous = OTHER

    fun scan(): List<HighlightSpan> {
        while (position < text.length) {
            val c = text[position]
            when {
                c == '#' -> comment()
                c == '"' -> string()
                c.isWhitespace() || c == ',' -> position++
                c.isNameStart() -> name()
                c.isDigit() || (c == '-' && position + 1 < text.length && text[position + 1].isDigit()) -> number()
                c == '$' || c == '@' -> sign(c)
                else -> punctuation(c)
            }
        }
        return spans
    }

    private fun Char.isNameStart() = this == '_' || this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isNamePart() = isNameStart() || this in '0'..'9'

    private fun add(
        start: Int,
        style: TokenStyle,
    ) {
        spans += HighlightSpan(start, position, style)
    }

    private fun comment() {
        val start = position
        while (position < text.length && text[position] != '\n') position++
        add(start, TokenStyle.COMMENT)
    }

    private fun string() {
        val start = position
        if (text.startsWith("\"\"\"", position)) {
            position += 3
            while (position < text.length && !text.startsWith("\"\"\"", position)) {
                position += if (text.startsWith("\\\"\"\"", position)) 4 else 1
            }
            position = minOf(text.length, position + 3)
        } else {
            position++
            while (position < text.length && text[position] != '"' && text[position] != '\n') {
                val escape = text[position] == '\\' && position + 1 < text.length && text[position + 1] != '\n'
                position += if (escape) 2 else 1
            }
            if (position < text.length && text[position] == '"') position++
        }
        add(start, TokenStyle.STRING)
        typeMode = false
        previous = OTHER
    }

    private fun number() {
        val start = position
        if (text[position] == '-') position++
        digits()
        if (position + 1 < text.length && text[position] == '.' && text[position + 1].isDigit()) {
            position++
            digits()
        }
        if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
            var end = position + 1
            if (end < text.length && (text[end] == '+' || text[end] == '-')) end++
            if (end < text.length && text[end].isDigit()) {
                position = end
                digits()
            }
        }
        add(start, TokenStyle.NUMBER)
        previous = OTHER
    }

    private fun digits() {
        while (position < text.length && text[position].isDigit()) position++
    }

    /** `$variable` and `@directive`, each styled with its sign. */
    private fun sign(sign: Char) {
        val start = position
        position++
        if (position < text.length && text[position].isNameStart()) {
            while (position < text.length && text[position].isNamePart()) position++
            add(start, TokenStyle.ANNOTATION)
        }
        previous = if (sign == '@') DIRECTIVE_NAME else OTHER
        if (sign == '@') {
            typeMode = false
            typeList = false
        }
    }

    /** The next character that is no blank, within the line if [sameLine]; `'\u0000'` at the end. */
    private fun nextChar(
        from: Int,
        sameLine: Boolean = false,
    ): Char {
        var i = from
        while (i < text.length && isBlank(text[i], sameLine)) i++
        return if (i < text.length) text[i] else '\u0000'
    }

    private fun isBlank(
        c: Char,
        sameLine: Boolean,
    ) = c == ' ' || c == '\t' || (!sameLine && (c == '\n' || c == '\r'))

    private fun top(): Char = if (stack.isEmpty()) OTHER else stack.last()

    private fun pop() {
        stack.setLength(stack.length - 1)
    }

    private fun resetDefinition() {
        stack.setLength(0)
        braces = 0
        typeMode = false
        typeList = false
        expectName = false
        definition = Definition.NONE
    }

    private fun name() {
        val start = position
        while (position < text.length && text[position].isNamePart()) position++
        val style = styleOf(text.substring(start, position), start)
        if (style != null) add(start, style)
        previous = NAME
    }

    private fun styleOf(
        word: String,
        start: Int,
    ): TokenStyle? {
        val following = nextChar(position)
        val kind = DEFINITIONS[word]
        if (kind != null && beginsDefinition(start, following)) {
            resetDefinition()
            definitionMayBegin = word == "extend"
            if (word != "extend") definition = kind
            expectName = word in NAMED
            return TokenStyle.KEYWORD
        }
        // a field type in a definition body is one type: the name after it is the next field
        if (typeMode && top() == BODY && definition == Definition.TYPE && previous in "n!]") typeMode = false
        val selection =
            top() == BODY && (definition == Definition.OPERATION || definition == Definition.FRAGMENT)
        return when {
            word == "on" && previous == '.' -> {
                typeMode = true
                TokenStyle.KEYWORD
            }

            word == "on" && stack.isEmpty() && !typeMode && !typeList && isHeadOfFragmentOrDirective() -> {
                if (definition == Definition.DIRECTIVE) typeList = true else typeMode = true
                TokenStyle.KEYWORD
            }

            word == "implements" && stack.isEmpty() && definition == Definition.TYPE -> {
                typeList = true
                TokenStyle.KEYWORD
            }

            word == "repeatable" && stack.isEmpty() && definition == Definition.DIRECTIVE -> {
                TokenStyle.KEYWORD
            }

            expectName -> {
                expectName = false
                TokenStyle.TYPE
            }

            stack.isEmpty() && typeList -> {
                TokenStyle.TYPE
            }

            following == ':' && stack.isNotEmpty() -> {
                TokenStyle.PROPERTY
            }

            typeMode -> {
                TokenStyle.TYPE
            }

            following == '(' && stack.isNotEmpty() && previous != '.' -> {
                TokenStyle.PROPERTY
            }

            selection && previous != '.' -> {
                TokenStyle.PROPERTY
            }

            word in VALUES && (top() in "([o" || previous == ':' || previous == '=') -> {
                TokenStyle.KEYWORD
            }

            else -> {
                null
            }
        }
    }

    private fun isHeadOfFragmentOrDirective() = definition == Definition.FRAGMENT || definition == Definition.DIRECTIVE

    /**
     * A definition keyword begins a definition at column 0 when a name, `{` or `@` follows on its line, and where one
     * may begin (outside all brackets, after a definition ended with its `}` or at the start) unless a `:` follows.
     */
    private fun beginsDefinition(
        start: Int,
        following: Char,
    ): Boolean {
        val columnZero = start == 0 || text[start - 1] == '\n'
        val next = nextChar(position, sameLine = true)
        if (columnZero && (next.isNameStart() || next == '{' || next == '@')) return true
        return definitionMayBegin && stack.isEmpty() && following != ':'
    }

    private fun punctuation(c: Char) {
        val start = position
        position++
        val before = previous
        previous = OTHER
        when (c) {
            '{' -> {
                open(before)
            }

            '}' -> {
                closeBraces()
                typeMode = false
            }

            '(' -> {
                openParenthesis(before)
            }

            ')' -> {
                if (top() == ARGUMENTS || top() == DEFINED) pop()
                typeMode = false
            }

            '[' -> {
                stack.append(LIST)
            }

            ']' -> {
                if (top() == LIST) pop()
                previous = ']'
            }

            ':' -> {
                val body = top() == BODY && (definition == Definition.TYPE || definition == Definition.SCHEMA)
                if (top() == DEFINED || body) typeMode = true
                previous = ':'
            }

            '=' -> {
                typeMode = false
                if (definition == Definition.UNION && stack.isEmpty()) typeList = true
                add(start, TokenStyle.OPERATOR)
                previous = '='
            }

            '!', '|', '&' -> {
                add(start, TokenStyle.OPERATOR)
                if (c == '!') previous = '!'
            }

            '.' -> {
                if (text.startsWith("...", start)) {
                    position = start + 3
                    add(start, TokenStyle.OPERATOR)
                    previous = '.'
                }
            }
        }
    }

    /** The parentheses of definitions hold types (`field(arg: T)`, `query Q($v: T)`), others hold values. */
    private fun openParenthesis(before: Char) {
        val defined =
            when (definition) {
                Definition.OPERATION, Definition.FRAGMENT -> stack.isEmpty() && before != DIRECTIVE_NAME
                Definition.TYPE -> before != DIRECTIVE_NAME
                Definition.DIRECTIVE -> stack.isEmpty() || before != DIRECTIVE_NAME
                else -> false
            }
        stack.append(if (defined) DEFINED else ARGUMENTS)
        typeMode = false
        typeList = false
    }

    /** `{` is an object value after `:` or `=` or inside a value, else the body of a definition or selection set. */
    private fun open(before: Char) {
        val value = before == ':' || before == '=' || top() == ARGUMENTS || top() == LIST || top() == OBJECT
        if (!value && stack.isEmpty() && definition == Definition.NONE) definition = Definition.OPERATION
        stack.append(if (value) OBJECT else BODY)
        braces++
        typeMode = false
        typeList = false
        expectName = false
    }

    /** `}` ends everything up to the nearest brace; without an open brace it is ignored. */
    private fun closeBraces() {
        if (braces == 0) return
        while (stack.isNotEmpty()) {
            val closed = stack.last()
            pop()
            if (closed == BODY || closed == OBJECT) break
        }
        braces--
        if (stack.isEmpty()) {
            definition = Definition.NONE
            definitionMayBegin = true
        }
    }
}
