package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import org.fife.ui.rsyntaxtextarea.TokenTypes
import java.util.TreeMap
import javax.swing.text.Segment

/**
 * [SyntaxHighlighter] based on the lexers of RSyntaxTextArea (ADR 0005). Only the lexers are used, no Swing
 * component. [syntaxStyle] is one of the `SyntaxConstants.SYNTAX_STYLE_*` values, e.g. `"text/python"`.
 *
 * Some lexers lag behind their language, so they can be corrected:
 * - [keywords], [types] and [builtins] override the lexer's style for these words;
 * - [tokenStyles] overrides the style of whole token types; `null` leaves that token type unstyled;
 * - an identifier in [stringPrefixes] directly before a string (like the `f` of `f'…'`) is part of the string;
 * - [rawStringDelimiter] (e.g. Kotlin's `"""`) marks everything up to the closing delimiter as string, for lexers
 *   that do not know multi-line raw strings;
 * - [lexerMasks] are regexes whose matches the lexer sees as blanks, for text it misreads and a line rule can style
 *   afterwards: Rust's `'a` lifetimes, which the lexer takes for the start of a character literal, or the `'` of C++'s
 *   `1'000`; every other offset stays the same;
 * - [lineRules] are regexes applied to every line; text in a named group whose name is a [TokenStyle.styleClass]
 *   (e.g. `(?<property>…)`) gets that style and replaces the lexer's spans there, except where the lexer found one
 *   of the [keptStyles] (by default comments and strings, so that e.g. a `<b>` in a string is no tag).
 *
 * Lexer errors are never shown: a viewer must not flag valid code that a lexer does not know yet.
 *
 * The configured languages are in the companion object, so RSyntaxTextArea stays confined to the `highlight` package.
 */
internal class RSyntaxHighlighter(
    private val syntaxStyle: String,
    private val keywords: Set<String> = emptySet(),
    private val types: Set<String> = emptySet(),
    private val builtins: Set<String> = emptySet(),
    private val tokenStyles: Map<Int, TokenStyle?> = emptyMap(),
    private val stringPrefixes: Set<String> = emptySet(),
    private val rawStringDelimiter: String? = null,
    private val lineRules: List<Regex> = emptyList(),
    private val lexerMasks: List<Regex> = emptyList(),
    private val keptStyles: Set<TokenStyle> = setOf(TokenStyle.COMMENT, TokenStyle.STRING),
) : SyntaxHighlighter {
    /** For each line rule, the styles that have a named group in it. */
    private val ruleStyles: List<Pair<Regex, List<TokenStyle>>> =
        lineRules.map { rule -> rule to TokenStyle.entries.filter { "(?<${it.styleClass}>" in rule.pattern } }

    override fun highlight(text: String): List<HighlightSpan> {
        val lexed = lex(text)
        if (lineRules.isEmpty()) return lexed
        // All three lists are sorted and free of overlaps, so each step is one pass over them.
        val ruled = applyLineRules(text).withoutOverlapsWith(lexed.filter { it.style in keptStyles })
        return merge(lexed.withoutOverlapsWith(ruled), ruled)
    }

    /** The spans of all line rules, sorted; an earlier rule's span wins over a later one's overlapping it. */
    private fun applyLineRules(text: String): List<HighlightSpan> {
        // By start; spans do not overlap, so their starts differ and the neighbours tell whether a new one overlaps.
        val spans = TreeMap<Int, HighlightSpan>()
        var lineStart = 0
        for (line in text.split('\n')) {
            val content = line.removeSuffix("\r")
            for ((rule, styles) in ruleStyles) {
                for (match in rule.findAll(content)) {
                    for (style in styles) {
                        val range = match.groups[style.styleClass]?.range ?: continue
                        if (range.isEmpty()) continue
                        val span = HighlightSpan(lineStart + range.first, lineStart + range.last + 1, style)
                        val before = spans.floorEntry(span.start)?.value
                        val after = spans.ceilingEntry(span.start)?.value
                        if ((before == null || before.end <= span.start) &&
                            (after == null || after.start >= span.end)
                        ) {
                            spans[span.start] = span
                        }
                    }
                }
            }
            lineStart += line.length + 1
        }
        return spans.values.toList()
    }

    /** These sorted, disjoint spans without those overlapping one of the sorted, disjoint [others]. */
    private fun List<HighlightSpan>.withoutOverlapsWith(others: List<HighlightSpan>): List<HighlightSpan> {
        var next = 0
        return filter { span ->
            // Spans of [others] ending before this one starts cannot overlap any later span either.
            while (next < others.size && others[next].end <= span.start) next++
            next == others.size || others[next].start >= span.end
        }
    }

    /** The spans of two sorted lists that do not overlap each other, in one sorted list. */
    private fun merge(
        first: List<HighlightSpan>,
        second: List<HighlightSpan>,
    ): List<HighlightSpan> {
        val merged = ArrayList<HighlightSpan>(first.size + second.size)
        var i = 0
        var j = 0
        while (i < first.size || j < second.size) {
            merged +=
                if (j == second.size ||
                    (i < first.size && first[i].start < second[j].start)
                ) {
                    first[i++]
                } else {
                    second[j++]
                }
        }
        return merged
    }

    private fun lex(text: String): List<HighlightSpan> {
        // The plain lexer styles nothing, and takes quadratic time on long lines with many backslashes.
        if (syntaxStyle == SyntaxConstants.SYNTAX_STYLE_NONE) return emptyList()
        // Lexers keep state in their token list, so every call gets its own instance.
        val lexer = TokenMakerFactory.getDefaultInstance().getTokenMaker(syntaxStyle)
        val chars = text.toCharArray()
        for (mask in lexerMasks) {
            for (match in mask.findAll(text)) {
                for (i in match.range) {
                    chars[i] = ' '
                }
            }
        }
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
                // Some lexers end a line with an empty token that only carries state, e.g. Markdown after ```ts.
                if (token.isPaintable && token.length() > 0) {
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
        // Some lexers report the start of a raw string (Go's backtick) as an error token.
        val startsString = styleOf(token.type) == TokenStyle.STRING || token.type == TokenTypes.ERROR_IDENTIFIER
        if (!startsString || !text.startsWith(delimiter, token.offset)) return -1
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

    private fun styleOf(type: Int): TokenStyle? = if (type in tokenStyles) tokenStyles[type] else defaultStyleOf(type)

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

            // Markup (XML, HTML): DTDs and processing instructions are shown like annotations, entities like numbers.
            TokenTypes.MARKUP_TAG_DELIMITER, TokenTypes.MARKUP_TAG_NAME, TokenTypes.MARKUP_CDATA_DELIMITER,
            -> TokenStyle.TAG

            TokenTypes.MARKUP_TAG_ATTRIBUTE -> TokenStyle.ATTRIBUTE

            TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, TokenTypes.MARKUP_CDATA -> TokenStyle.STRING

            TokenTypes.MARKUP_DTD, TokenTypes.MARKUP_PROCESSING_INSTRUCTION -> TokenStyle.ANNOTATION

            TokenTypes.MARKUP_ENTITY_REFERENCE -> TokenStyle.NUMBER

            else -> null
        }

    /** The configured languages, defined by family in [WebLanguages], [ScriptLanguages] and so on. */
    companion object {
        val java = ScriptLanguages.java
        val kotlin = ScriptLanguages.kotlin
        val groovy = ScriptLanguages.groovy
        val python = ScriptLanguages.python
        val sql = ScriptLanguages.sql
        val shell = ScriptLanguages.shell
        val powerShell = ScriptLanguages.powerShell

        val javaScript = WebLanguages.javaScript
        val javaScriptReact = WebLanguages.javaScriptReact
        val typeScript = WebLanguages.typeScript
        val typeScriptReact = WebLanguages.typeScriptReact
        val json = WebLanguages.json
        val xml = WebLanguages.xml
        val html = WebLanguages.html
        val singleFileComponent = WebLanguages.singleFileComponent
        val css = WebLanguages.css
        val less = WebLanguages.less
        val scss = WebLanguages.scss
        val sass = WebLanguages.sass

        val yaml = ConfigLanguages.yaml
        val dockerfile = ConfigLanguages.dockerfile
        val makefile = ConfigLanguages.makefile
        val ini = ConfigLanguages.ini
        val properties = ConfigLanguages.properties
        val codeowners = ConfigLanguages.codeowners
        val gitattributes = ConfigLanguages.gitattributes
        val mailmap = ConfigLanguages.mailmap
        val ignoreFile = ConfigLanguages.ignoreFile
        val blameIgnoreRevs = ConfigLanguages.blameIgnoreRevs
        val sshConfig = ConfigLanguages.sshConfig
        val caddyfile = ConfigLanguages.caddyfile
        val cookies = ConfigLanguages.cookies
        val env = ConfigLanguages.env
        val pem = ConfigLanguages.pem

        val markdown = MarkupLanguages.markdown
        val mdx = MarkupLanguages.mdx
        val mermaid = MarkupLanguages.mermaid

        /** The highlighter for a code fence's [language] (` ```ts `), lower case, or `null` if there is none. */
        fun forLanguage(language: String): SyntaxHighlighter? = MarkupLanguages.forLanguage(language)
    }
}
