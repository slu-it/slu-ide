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
 * Some lexers lag behind their language, so words can be reclassified: [keywords] and [builtins] override the
 * lexer's style for these words, and an identifier in [stringPrefixes] directly before a string (like the `f` of
 * `f'…'`) is styled as part of the string. Lexer errors are never shown: a viewer must not flag valid code that a
 * lexer does not know yet.
 *
 * The configured languages are in the companion object, so RSyntaxTextArea stays confined to this file.
 */
internal class RSyntaxHighlighter(
    private val syntaxStyle: String,
    private val keywords: Set<String> = emptySet(),
    private val builtins: Set<String> = emptySet(),
    private val stringPrefixes: Set<String> = emptySet(),
) : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        // Lexers keep state in their token list, so every call gets its own instance.
        val lexer = TokenMakerFactory.getDefaultInstance().getTokenMaker(syntaxStyle)
        val chars = text.toCharArray()
        val spans = mutableListOf<HighlightSpan>()
        var lineStart = 0
        // The last token type of a line tells the lexer how the next line starts, e.g. inside a """ string.
        var state = TokenTypes.NULL
        while (lineStart <= chars.size) {
            val newline = text.indexOf('\n', lineStart).let { if (it < 0) chars.size else it }
            val lineEnd = if (newline > lineStart && chars[newline - 1] == '\r') newline - 1 else newline
            var token: Token? = lexer.getTokenList(Segment(chars, lineStart, lineEnd - lineStart), state, lineStart)
            var lastType = TokenTypes.NULL
            while (token != null) {
                lastType = token.type
                if (token.isPaintable) {
                    styleOf(token)?.let { spans += HighlightSpan(token.offset, token.offset + token.length(), it) }
                }
                token = token.nextToken
            }
            state = lastType
            lineStart = newline + 1
        }
        return spans
    }

    private fun styleOf(token: Token): TokenStyle? {
        if (token.type !in wordTypes) return styleOf(token.type)
        val word = token.lexeme
        return when {
            word in builtins -> TokenStyle.BUILTIN
            word in keywords -> TokenStyle.KEYWORD
            word.lowercase() in stringPrefixes && isStringStart(token.nextToken) -> TokenStyle.STRING
            else -> styleOf(token.type)
        }
    }

    private val wordTypes =
        setOf(TokenTypes.IDENTIFIER, TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.FUNCTION)

    private fun isStringStart(token: Token?): Boolean = token != null && styleOf(token.type) == TokenStyle.STRING

    private fun styleOf(type: Int): TokenStyle? =
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
