package dev.sluide.fileviewers

import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import org.fife.ui.rsyntaxtextarea.TokenTypes
import javax.swing.text.Segment

/**
 * [SyntaxHighlighter] based on the lexers of RSyntaxTextArea (ADR 0005). Only the lexers are used, no Swing
 * component. [syntaxStyle] is one of the `SyntaxConstants.SYNTAX_STYLE_*` values, e.g. `"text/python"`.
 *
 * Some lexers lag behind their language, so words can be reclassified: [keywords], [types] and [builtins] override
 * the lexer's style for these words, [tokenStyles] overrides the style of whole token types, and an identifier in [stringPrefixes] directly before a string (like the `f` of
 * `f'…'`) is styled as part of the string. For lexers that do not know multi-line raw strings, [rawStringDelimiter]
 * (e.g. Kotlin's `"""`) marks everything up to the closing delimiter as string. Lexer errors are never shown: a viewer
 * must not flag valid code that a lexer does not know yet.
 *
 * The configured languages are in the companion object, so RSyntaxTextArea stays confined to this file.
 */
internal class RSyntaxHighlighter(
    private val syntaxStyle: String,
    private val keywords: Set<String> = emptySet(),
    private val types: Set<String> = emptySet(),
    private val builtins: Set<String> = emptySet(),
    private val tokenStyles: Map<Int, TokenStyle> = emptyMap(),
    private val stringPrefixes: Set<String> = emptySet(),
    private val rawStringDelimiter: String? = null,
) : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        // Lexers keep state in their token list, so every call gets its own instance.
        val lexer = TokenMakerFactory.getDefaultInstance().getTokenMaker(syntaxStyle)
        val chars = text.toCharArray()
        val spans = mutableListOf<HighlightSpan>()
        // Lexing goes line by line; `start` is where lexing continues, usually the start of a line.
        var start = 0
        // The last token type of a line tells the lexer how the next line starts, e.g. inside a """ string.
        var state = TokenTypes.NULL
        while (start <= chars.size) {
            val newline = text.indexOf('\n', start).let { if (it < 0) chars.size else it }
            val lineEnd = if (newline > start && chars[newline - 1] == '\r') newline - 1 else newline
            var token: Token? = lexer.getTokenList(Segment(chars, start, lineEnd - start), state, start)
            var lastType = TokenTypes.NULL
            var rawStringEnd = -1
            while (token != null) {
                lastType = token.type
                if (token.isPaintable) {
                    rawStringEnd = rawStringEnd(text, token)
                    if (rawStringEnd >= 0) {
                        spans += HighlightSpan(token.offset, rawStringEnd, TokenStyle.STRING)
                        break
                    }
                    styleOf(token)?.let { spans += HighlightSpan(token.offset, token.offset + token.length(), it) }
                }
                token = token.nextToken
            }
            if (rawStringEnd >= 0) {
                // Continue right after the raw string, which may be on a later line, with a fresh lexer state.
                start = rawStringEnd
                state = TokenTypes.NULL
            } else {
                start = newline + 1
                state = lastType
            }
        }
        return spans
    }

    /** End offset of the raw string starting at [token], or -1 if [token] does not start one. */
    private fun rawStringEnd(
        text: String,
        token: Token,
    ): Int {
        val delimiter = rawStringDelimiter ?: return -1
        if (styleOf(token.type) != TokenStyle.STRING || !text.startsWith(delimiter, token.offset)) return -1
        val close = text.indexOf(delimiter, token.offset + delimiter.length)
        if (close < 0) return text.length
        // Kotlin allows extra quotes right before the closing delimiter: """a"""" ends after the last quote.
        var end = close + delimiter.length
        while (end < text.length && text[end] == delimiter.last()) end++
        return end
    }

    private fun styleOf(token: Token): TokenStyle? {
        if (token.type !in wordTypes) return styleOf(token.type)
        val word = token.lexeme
        return when {
            word in builtins -> TokenStyle.BUILTIN
            word in keywords -> TokenStyle.KEYWORD
            word in types -> TokenStyle.TYPE
            word.lowercase() in stringPrefixes && isStringStart(token.nextToken) -> TokenStyle.STRING
            else -> styleOf(token.type)
        }
    }

    private val wordTypes =
        setOf(TokenTypes.IDENTIFIER, TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.FUNCTION)

    private fun isStringStart(token: Token?): Boolean = token != null && styleOf(token.type) == TokenStyle.STRING

    private fun styleOf(type: Int): TokenStyle? = tokenStyles[type] ?: defaultStyleOf(type)

    private fun defaultStyleOf(type: Int): TokenStyle? =
        when (type) {
            TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.LITERAL_BOOLEAN,
            TokenTypes.PREPROCESSOR,
            -> TokenStyle.KEYWORD

            TokenTypes.DATA_TYPE -> TokenStyle.TYPE

            TokenTypes.FUNCTION -> TokenStyle.BUILTIN

            // Unterminated strings and numbers the lexer does not know (e.g. Python's 1_000) still look like these.
            TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
            TokenTypes.REGEX, TokenTypes.ERROR_STRING_DOUBLE, TokenTypes.ERROR_CHAR,
            -> TokenStyle.STRING

            TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
            TokenTypes.LITERAL_NUMBER_HEXADECIMAL, TokenTypes.ERROR_NUMBER_FORMAT,
            -> TokenStyle.NUMBER

            TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
            TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT,
            -> TokenStyle.COMMENT

            TokenTypes.ANNOTATION -> TokenStyle.ANNOTATION

            TokenTypes.OPERATOR -> TokenStyle.OPERATOR

            else -> null
        }

    companion object {
        val java = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_JAVA)

        /** The JavaScript lexer treats the module keyword `from` and `undefined` as identifiers. */
        val javaScript =
            RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT, keywords = setOf("from", "undefined"))

        /**
         * The TypeScript lexer misses many keywords and types of current TypeScript and reports decorators as markup
         * attributes.
         */
        val typeScript =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT,
                keywords =
                    setOf(
                        "type",
                        "readonly",
                        "constructor",
                        "as",
                        "is",
                        "declare",
                        "keyof",
                        "namespace",
                        "from",
                        "satisfies",
                        "infer",
                        "override",
                        "asserts",
                        "undefined",
                    ),
                types = setOf("unknown", "never", "object", "symbol", "bigint"),
                tokenStyles = mapOf(TokenTypes.MARKUP_TAG_ATTRIBUTE to TokenStyle.ANNOTATION),
            )

        /**
         * JSON with `//` comments, which many config files (e.g. `tsconfig.json`) use; plain JSON lexes the same.
         * The lexer reports object keys as variables.
         */
        val json =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS,
                tokenStyles = mapOf(TokenTypes.VARIABLE to TokenStyle.PROPERTY),
            )

        /** The Kotlin lexer knows no raw strings and misses `package`. */
        val kotlin =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_KOTLIN,
                keywords = setOf("package"),
                rawStringDelimiter = "\"\"\"",
            )

        /** The Python lexer predates Python 3 in places; these corrections bring it up to date. */
        val python =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_PYTHON,
                keywords = setOf("async", "await", "nonlocal"),
                builtins = setOf("print", "exec"),
                stringPrefixes = setOf("f", "r", "b", "u", "rb", "br", "fr", "rf"),
            )
    }
}
