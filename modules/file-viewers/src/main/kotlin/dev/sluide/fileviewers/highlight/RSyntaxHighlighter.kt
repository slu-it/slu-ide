package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.BicepHighlighter
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
 * - [lineRules] are regexes applied to every line; text in a named group whose name is a [TokenStyle.styleClass]
 *   (e.g. `(?<property>…)`) gets that style and replaces the lexer's spans there, except where the lexer found one
 *   of the [keptStyles] (by default comments and strings, so that e.g. a `<b>` in a string is no tag).
 *
 * Lexer errors are never shown: a viewer must not flag valid code that a lexer does not know yet.
 *
 * The configured languages are in the companion object, so RSyntaxTextArea stays confined to this file.
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

    companion object {
        val java = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_JAVA)

        /** Where markup may start in code: at the line start or after whitespace, brackets or an operator. */
        private const val TAG_START = """(?:^|(?<=[\s(,={}?:&|>]))"""

        /** A JSX/HTML attribute: name, optionally with a quoted or `{…}` value; or a `{...spread}`. */
        private const val ATTRIBUTE =
            """(?:[A-Za-z_][\w:.-]*(?:=(?:"[^"]*"|'[^']*'|\{(?:[^{}]|\{[^{}]*})*}))?|\{\.\.\.[^{}]*})"""

        /** Name of an opening tag, e.g. `<Item.Row`. */
        private const val OPEN_TAG_NAME = """<[A-Za-z][\w.:-]*"""

        /** What follows an opening tag's name up to its end on the same line, not followed by `(`. */
        private const val OPEN_TAG_REST = """(?:\s+$ATTRIBUTE)*\s*/?>(?!\s*\()"""

        /**
         * Tags in JSX and in HTML whose lexer does not know a tag name (`<template>`, components like `<MyCard>`).
         * Only whole tags count, so that code like `a <b && c > d` or a generic like `<T>(x: T) => T` is no tag:
         * - an opening tag complete on its line, not followed by `(`: its `<name` and its closing `>` or `/>`;
         * - an opening tag whose attributes continue on the next lines: its `<name`;
         * - a closing tag `</name>`, and a `>` or `/>` alone on its line, which ends a tag spanning lines.
         */
        private val markupTags =
            listOf(
                Regex("""$TAG_START(?<tag>$OPEN_TAG_NAME)(?=$OPEN_TAG_REST|(?:\s+$ATTRIBUTE)*\s*$)"""),
                Regex("""$TAG_START$OPEN_TAG_NAME(?:\s+$ATTRIBUTE)*\s*(?<tag>/?>)(?!\s*\()"""),
                Regex("""(?<tag></[A-Za-z][\w.:-]*\s*>)"""),
                Regex("""^\s*(?<tag>/?>)\s*$"""),
            )

        /**
         * JSX elements on top of JavaScript or TypeScript, which neither lexer knows: [markupTags], fragments
         * (`<>`, `</>`) and attributes with a value, either inside a tag started on the same line or on a line of
         * their own within a tag spanning lines. Boolean attributes are not styled.
         */
        private val jsxRules =
            markupTags +
                listOf(
                    Regex("""$TAG_START(?<tag></?>)"""),
                    Regex(
                        """(?<=(?:^|[\s(,={}?:&|>])<[A-Za-z][\w.:-]{0,100}\s[^<>]{0,300})""" +
                            """(?<attribute>[A-Za-z_][\w:-]*)(?==["'{])""",
                    ),
                    Regex("""^\s*(?<attribute>[A-Za-z_][\w:-]*)=(?:"[^"]*"|'[^']*'|\{.*})\s*(?:/?>)?\s*$"""),
                )

        /**
         * After `= <` the JavaScript and TypeScript lexers switch to E4X markup until the end of the line, so a generic
         * like `= <T>(x: T) => x` would look like a tag. With JSX, tags come from [jsxRules] only.
         */
        private val e4xMarkup: Map<Int, TokenStyle?> =
            mapOf(TokenTypes.MARKUP_TAG_DELIMITER to null, TokenTypes.MARKUP_TAG_NAME to null)

        /** The JavaScript lexer treats the module keyword `from` and `undefined` as identifiers. */
        private fun javaScript(jsx: Boolean) =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT,
                keywords = setOf("from", "undefined"),
                tokenStyles = if (jsx) e4xMarkup else emptyMap(),
                lineRules = if (jsx) jsxRules else emptyList(),
            )

        val javaScript = javaScript(jsx = false)

        val javaScriptReact = javaScript(jsx = true)

        /**
         * The TypeScript lexer misses many keywords and types of current TypeScript and reports decorators as markup
         * attributes.
         */
        private fun typeScript(jsx: Boolean) =
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
                tokenStyles =
                    mapOf(
                        TokenTypes.MARKUP_TAG_ATTRIBUTE to TokenStyle.ANNOTATION,
                    ) + if (jsx) e4xMarkup else emptyMap(),
                lineRules = if (jsx) jsxRules else emptyList(),
            )

        val typeScript = typeScript(jsx = false)

        val typeScriptReact = typeScript(jsx = true)

        /**
         * JSON with `//` comments, which many config files (e.g. `tsconfig.json`) use; plain JSON lexes the same.
         * The lexer reports object keys as variables.
         */
        val json =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS,
                tokenStyles = mapOf(TokenTypes.VARIABLE to TokenStyle.PROPERTY),
            )

        val xml = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_XML)

        /** Also lexes embedded `<script>` (JavaScript) and `<style>` (CSS) content. */
        val html = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_HTML)

        /**
         * Vue and Svelte components: HTML, whose lexer styles tag names it does not know (`<template>`, components
         * like `<MyButton>`) as attributes, so [markupTags] make them tags.
         */
        val singleFileComponent = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_HTML, lineRules = markupTags)

        /**
         * Markdown: headings and bold as keywords, emphasis as type, inline HTML as markup with component names made
         * tags by [markupTags]. The lexer reports inline and fenced code as preprocessor, shown as string here, and
         * a link's or image's text and target as regex and annotation, both shown as `attribute`. Fenced code with a
         * language that [forLanguage] knows is highlighted as that language ([FencedCodeHighlighter]).
         */
        private fun markdown(lineRules: List<Regex>): SyntaxHighlighter =
            FencedCodeHighlighter(
                RSyntaxHighlighter(
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    tokenStyles =
                        mapOf(
                            TokenTypes.PREPROCESSOR to TokenStyle.STRING,
                            TokenTypes.REGEX to TokenStyle.ATTRIBUTE,
                            TokenTypes.ANNOTATION to TokenStyle.ATTRIBUTE,
                        ),
                    lineRules = markupTags + lineRules,
                ),
                ::forLanguage,
            )

        val markdown = markdown(lineRules = emptyList())

        /** MDX: Markdown with `import`/`export` lines and JSX components. */
        val mdx = markdown(lineRules = listOf(Regex("""^(?<keyword>import|export)\b""")))

        /** The highlighter for a code fence's [language] (` ```ts `), lower case, or `null` if there is none. */
        fun forLanguage(language: String): SyntaxHighlighter? =
            when (language) {
                "java" -> java
                "kotlin", "kt", "kts" -> kotlin
                "python", "py" -> python
                "javascript", "js", "mjs", "cjs" -> javaScript
                "jsx" -> javaScriptReact
                "typescript", "ts" -> typeScript
                "tsx" -> typeScriptReact
                "json", "jsonc" -> json
                "xml", "svg" -> xml
                "html" -> html
                "vue", "svelte" -> singleFileComponent
                "yaml", "yml" -> yaml
                "css" -> css
                "scss" -> scss
                "sass" -> sass
                "less" -> less
                "markdown", "md" -> markdown
                "mdx" -> mdx
                "sql" -> sql
                "kql", "kusto", "csl" -> KqlHighlighter
                "bash", "sh", "shell", "zsh", "ksh" -> shell
                "powershell", "ps1", "pwsh" -> powerShell
                "dockerfile", "docker", "containerfile" -> dockerfile
                "makefile", "make", "mk" -> makefile
                "ini", "cfg", "conf", "editorconfig", "gitconfig" -> ini
                "gitignore" -> ignoreFile
                "ssh-config", "sshconfig", "ssh_config", "sshd_config" -> sshConfig
                "caddyfile", "caddy" -> caddyfile
                "mermaid", "mmd" -> mermaid
                "bicep", "bicepparam" -> BicepHighlighter
                "dotenv", "env" -> env
                "toml" -> TomlHighlighter
                else -> null
            }

        /** SCSS and Sass variables (`$primary`), styled like custom properties. */
        private val sassVariable = Regex("""(?<annotation>\$[\w-]+)""")

        /** Custom properties (`--main`), styled like variables. */
        private val cssCustomProperty = Regex("""(?<![\w-])(?<annotation>--[\w-]+)""")

        /**
         * CSS at-rules, which the LESS lexer reports either as regexes (`@media`) or as LESS variables (`@supports`).
         * Listed, since LESS variables look the same.
         */
        private val cssAtRule =
            Regex(
                """(?<![\w-])(?<keyword>@(?:charset|container|counter-style|font-face|font-feature-values|import|""" +
                    """keyframes|layer|media|namespace|page|property|scope|starting-style|supports|""" +
                    """-webkit-keyframes|plugin))(?![\w-])""",
            )

        /**
         * Stylesheets use the LESS lexer, which copes better with nesting and units than the CSS one. It reports
         * at-rules like `@media` as regexes; `#id`s become annotations, like variables.
         */
        private fun stylesheet(
            tokenStyles: Map<Int, TokenStyle?> = emptyMap(),
            lineRules: List<Regex> = emptyList(),
        ) = RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_LESS,
            tokenStyles = mapOf(TokenTypes.REGEX to TokenStyle.KEYWORD) + tokenStyles,
            lineRules = listOf(cssCustomProperty, cssAtRule) + lineRules,
        )

        /** Plain CSS has no LESS variables: what the lexer takes for one is an at-rule (`@supports`). */
        val css = stylesheet(tokenStyles = mapOf(TokenTypes.VARIABLE to TokenStyle.KEYWORD))

        /** LESS variables (`@color`) are styled like CSS custom properties. */
        val less = stylesheet(tokenStyles = mapOf(TokenTypes.VARIABLE to TokenStyle.ANNOTATION))

        /**
         * The lexer reads SCSS's `@mixin`, `@include` etc. as LESS variables, so they are made keywords again; `$`
         * variables (also inside `#{…}`) are styled like custom properties.
         */
        val scss =
            stylesheet(
                tokenStyles = mapOf(TokenTypes.VARIABLE to TokenStyle.KEYWORD),
                lineRules = listOf(sassVariable),
            )

        /**
         * Indented Sass has no braces, so the lexer reports every word as a selector (`type`). That style is dropped
         * and line rules restyle properties (`keyword`), mixin definitions and uses (`=m`, `+m`), variables and
         * selector lines: lines without a `: ` that do not start a rule, variable or comment.
         */
        val sass =
            stylesheet(
                tokenStyles = mapOf(TokenTypes.DATA_TYPE to null, TokenTypes.VARIABLE to TokenStyle.KEYWORD),
                lineRules =
                    listOf(
                        sassVariable,
                        Regex("""^\s*(?<keyword>[=+][\w-]+)"""),
                        Regex("""^\s*(?<keyword>[\w-]+)\s*:(?=\s|$)"""),
                        Regex("""^(?!.*:(?:\s|$))\s*(?<type>[^\s@=+$/](?:(?!//)[^;])*?)\s*(?://.*)?$"""),
                    ),
            )

        /** Start of a YAML value: after `key:` or a list item's `-`. */
        private const val YAML_VALUE = """(?:^\s*-|:)[ \t]+"""

        /** End of a YAML value: optional trailing comment. */
        private const val YAML_END = """[ \t]*(?:#.*)?$"""

        /**
         * The YAML lexer only knows comments and quoted strings, so line rules add keys, numbers, booleans/null,
         * anchors, aliases, tags and document markers. Block scalar content (after `|` or `>`) is not recognised.
         */
        val yaml =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_YAML,
                // Quoted keys are strings to the lexer; the key rule makes them properties.
                keptStyles = setOf(TokenStyle.COMMENT),
                lineRules =
                    listOf(
                        // Key: quoted, or plain (not starting with an indicator character), up to ": " or a final ":".
                        Regex(
                            """^\s*(?:-\s+)*(?<property>"[^"]*"|'[^']*'|""" +
                                """[^\s#'"\[\]{}&*!|>%@`,-][^#]*?)\s*:(?=\s|$)""",
                        ),
                        Regex(
                            YAML_VALUE +
                                """(?<number>[-+]?(?:0x[0-9a-fA-F]+|0o[0-7]+|\d[\d_]*(?:\.\d*)?(?:[eE][-+]?\d+)?""" +
                                """|\.\d+(?:[eE][-+]?\d+)?|\.(?:inf|Inf|INF|nan|NaN|NAN)))""" + YAML_END,
                        ),
                        Regex(
                            YAML_VALUE + """(?<keyword>true|True|TRUE|false|False|FALSE|null|Null|NULL|~)""" + YAML_END,
                        ),
                        Regex("""(?:^|\s)(?<annotation>[&*][\w.-]+|!!?[\w/.-]*)"""),
                        Regex("""^(?<keyword>---|\.\.\.)(?=\s|$)"""),
                    ),
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

        // Shell, SQL and configuration files.

        /**
         * The whitespace-separated [words] in upper and in lower case, for case-insensitive languages: the
         * corrections only match words as written.
         */
        private fun upperAndLower(words: String): Set<String> =
            words.trim().split(Regex("""\s+""")).flatMapTo(HashSet()) { listOf(it.uppercase(), it.lowercase()) }

        /**
         * The SQL lexer misses many keywords of current dialects (`LIMIT`, `RETURNING`, `TRUE`, window functions) and
         * mixes data types between keywords and plain words, so all common ones are made types. Aggregates and other
         * functions are builtins. It reads the `.` of `a.id` as a number, which a line rule makes an operator.
         */
        val sql =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_SQL,
                keywords =
                    upperAndLower(
                        """
                        TRUE FALSE DEFAULT IF LIMIT OFFSET RETURNING BEGIN COMMIT ROLLBACK VIEW REPLACE CONFLICT DO
                        NOTHING EXCEPT INTERSECT FULL CROSS NATURAL OVER PARTITION WINDOW ROWS UNBOUNDED PRECEDING
                        FOLLOWING RECURSIVE TEMPORARY CASCADE RESTRICT CHECK SCHEMA TRIGGER FUNCTION PROCEDURE RETURNS
                        LANGUAGE DECLARE EXECUTE GRANT REVOKE TRUNCATE ILIKE SIMILAR NULLS FETCH ONLY LATERAL CAST
                        COLLATE TRANSACTION SAVEPOINT EXPLAIN ANALYZE SEQUENCE ENUM EXTENSION RENAME TO
                        """,
                    ),
                types =
                    upperAndLower(
                        """
                        INTEGER INT BIGINT SMALLINT TINYINT SERIAL BIGSERIAL VARCHAR CHAR CHARACTER VARYING TEXT BOOLEAN
                        BOOL DATE TIME TIMESTAMP TIMESTAMPTZ INTERVAL NUMERIC DECIMAL REAL FLOAT DOUBLE PRECISION UUID
                        JSON JSONB BLOB BYTEA CLOB MONEY NVARCHAR DATETIME
                        """,
                    ),
                builtins =
                    upperAndLower(
                        """
                        COUNT SUM MIN MAX COALESCE NULLIF GREATEST LEAST LENGTH CONCAT NOW ROUND ABS ROW_NUMBER RANK
                        DENSE_RANK LAG LEAD STRING_AGG ARRAY_AGG JSON_AGG DATE_TRUNC TO_CHAR
                        """,
                    ),
                lineRules = listOf(Regex("""(?<=[\w"\]])(?<operator>\.)(?=[A-Za-z_"*\[])""")),
            )

        /**
         * Shell scripts. The lexer reports variables (`$NAME`, `${1:-x}`, the `NAME` of `NAME=value`) as variables,
         * styled like those of stylesheets, and the shebang as preprocessor, shown as a comment. It takes `function`
         * for a command and misses the builtins `local`, `declare` and `source`.
         */
        val shell =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
                keywords = setOf("function"),
                builtins = setOf("local", "declare", "source", "shopt", "builtin", "mapfile"),
                tokenStyles =
                    mapOf(TokenTypes.VARIABLE to TokenStyle.ANNOTATION, TokenTypes.PREPROCESSOR to TokenStyle.COMMENT),
            )

        /** A `[` starting a PowerShell type or attribute, not an index like `$arr[i]` or `(…)[0]`. */
        private const val PS_TYPE_START = """(?<![\w\])}$:])\["""

        /**
         * PowerShell. The lexer splits a variable into an error `$` and a plain name, so a line rule makes variables
         * (`$name`, `$env:PATH`, `${name}`) annotations, like shell variables; `$true`, `$false` and `$null`, in any
         * case, are keywords. Type names in brackets (`[string]`, `[int[]]`) become types, attributes
         * (`[CmdletBinding()]`) annotations, but not indexes (`$arr[i]`). Known gap: the content of here-strings
         * (`@"…"@`) is lexed as code.
         */
        val powerShell =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_POWERSHELL,
                lineRules =
                    listOf(
                        Regex("""(?i)(?<![\w$])(?<keyword>\$(?:true|false|null))(?![\w:])"""),
                        Regex("""(?<![\w$])(?<annotation>\$(?:\{[^{}]*+}|[\w:]+))"""),
                        Regex("""$PS_TYPE_START(?<type>[A-Za-z_][\w.]*+)(?=(?:\[\])?\])"""),
                        Regex("""$PS_TYPE_START(?<annotation>[A-Za-z_][\w.]*+)(?=\()"""),
                    ),
            )

        /**
         * Dockerfiles. The lexer misses the instructions `HEALTHCHECK` and `SHELL` and the `AS` naming a build stage;
         * variables (`$VAR`, `${VAR}`) outside strings become annotations, like shell variables.
         */
        val dockerfile =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_DOCKERFILE,
                lineRules =
                    listOf(
                        Regex("""(?i)^\s*+(?<keyword>HEALTHCHECK|SHELL)(?=\s|$)"""),
                        // `AS name` ends the line: found from the end, so options before the image cost nothing.
                        Regex("""(?i)^\s*+FROM\s.*\s(?<keyword>AS)\s++\S++\s*+$"""),
                        Regex("""(?<annotation>\$(?:\{[^{}]*+}|\w+))"""),
                    ),
            )

        /**
         * Makefiles. The lexer reports targets (`all:`) as preprocessor, shown as types here, and misses directives
         * such as `include`, `export` and `override`. Variable references (`$(CC)`, `${OBJ}`) are styled like shell
         * variables, and so are automatic variables (`$@`, `$<`), which the lexer does not know. A line rule makes
         * whole targets types, since the lexer splits pattern rules like `%.o:`.
         */
        val makefile =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_MAKEFILE,
                keywords =
                    setOf("include", "-include", "sinclude", "export", "unexport", "override", "vpath", "undefine"),
                tokenStyles =
                    mapOf(TokenTypes.VARIABLE to TokenStyle.ANNOTATION, TokenTypes.PREPROCESSOR to TokenStyle.TYPE),
                lineRules =
                    listOf(
                        // Up to the colon of a rule, which `:=` and `::=` assignments are not.
                        Regex("""^(?<type>[^\s#=:][^#=:]*?::?)(?=\s|$)"""),
                        Regex("""(?<annotation>\$[@^<?*%+|])"""),
                    ),
            )

        /**
         * INI-style configuration (`.ini`, and `.cfg`, `.editorconfig`, `.gitconfig` and the like). The lexer knows
         * `;` and `#` comments and reports sections as preprocessor, shown as types. It also reports every word
         * before the `=`, and every word of lines without one (continued values), as a data type; that is dropped,
         * and line rules make keys properties: what comes before the first `=` (not a `>=`, `<=`, `!=`, `~=` or
         * `==`), or before a `:` on lines without `=`.
         */
        val ini =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_INI,
                tokenStyles = mapOf(TokenTypes.PREPROCESSOR to TokenStyle.TYPE, TokenTypes.DATA_TYPE to null),
                lineRules =
                    listOf(
                        // Keys end with a non-blank, so that no lazy match runs over the blanks before the `=`.
                        Regex("""^\s*+(?<property>[^\s=;#\[](?:[^=]*[^\s=])?)\s*+(?<![<>!~=])=(?!=)"""),
                        Regex("""^\s*+(?<property>[^\s=:;#\[](?:[^=:]*[^\s=:])?)\s*+:(?!.*=)"""),
                    ),
            )

        // Small formats without a lexer: line rules on plain text, the comment rule first, since rules do not overlap.

        /**
         * Ignore files (`.gitignore`, `.dockerignore`, …): `#` comments at the start of a line, the `!` negating a
         * pattern as keyword, and the wildcards `**`, `*` and `?` (not escaped with `\`) as operators.
         */
        val ignoreFile =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_NONE,
                lineRules =
                    listOf(
                        Regex("""^(?<comment>#.*)"""),
                        Regex("""^(?<keyword>!)"""),
                        Regex("""(?<!\\)(?<operator>\*\*|[*?])"""),
                    ),
            )

        /** Git's `.git-blame-ignore-revs`: `#` comments, also after a commit, and commit hashes as numbers. */
        val blameIgnoreRevs =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_NONE,
                lineRules =
                    listOf(
                        Regex("""(?<comment>#.*)"""),
                        Regex("""^\s*+(?<number>[0-9a-fA-F]{7,64})(?=\s|#|$)"""),
                    ),
            )

        /**
         * OpenSSH's `sshd_config` and `ssh_config`: `#` comment lines, the first word of a line (the option) as
         * keyword, and what a `Host` or `Match` line applies to as type, since it starts a block.
         */
        val sshConfig =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_NONE,
                lineRules =
                    listOf(
                        Regex("""^\s*+(?<comment>#.*)"""),
                        Regex("""(?i)^\s*+(?:Host|Match)(?:\s*+=\s*+|\s++)(?<type>(?:.*\S)?)"""),
                        Regex("""^\s*+(?<keyword>[A-Za-z][A-Za-z0-9]*+)"""),
                    ),
            )

        /**
         * Caddyfiles: `#` comments (at the start of a line or after whitespace), site addresses and snippets (an
         * unindented line opening a block) as types, quoted and backtick strings, directives (the first word of a
         * line) as keywords, and named matchers (`@api`) and placeholders (`{path}`, `{$ENV}`) as annotations.
         * Known gaps: a site address without a block (a Caddyfile serving one site) shows as a directive, and
         * placeholders inside quotes stay part of the string.
         */
        val caddyfile =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_NONE,
                lineRules =
                    listOf(
                        Regex("""^\s*+(?<comment>#.*)"""),
                        Regex("""^(?<type>[^\s#{}@](?:[^{]*[^\s{])?)\s*+\{\s*+$"""),
                        // Up to a quote after an even number of backslashes. No loop over alternatives, which Java runs
                        // in quadratic time or recursively, and no string starts at an escaped quote.
                        Regex("""(?<!\\)(?<string>".*?(?<!\\)(?:\\\\)*+"|`[^`]*+`)"""),
                        Regex("""(?<=\s)(?<comment>#.*)"""),
                        Regex("""^\s*+(?<keyword>[a-z_][\w-]*+)(?=\s|$)"""),
                        Regex("""(?<![^\s(])(?<annotation>@[\w-]+)"""),
                        Regex("""(?<annotation>\{[^\s{}]+})"""),
                    ),
            )

        /** Statements of Mermaid's diagrams, the first word of a line. */
        private const val MERMAID_STATEMENTS =
            "classDef|class|style|linkStyle|subgraph|end|direction|click|participant|actor|loop|alt|else|opt|par|" +
                "and|critical|option|break|rect|box|create|destroy|Note|note|activate|deactivate|autonumber|title|" +
                "section|dateFormat|axisFormat|tickInterval|excludes|includes|todayMarker|weekday|state|namespace|" +
                "commit|branch|checkout|switch|merge|cherry-pick|accTitle|accDescr|showData"

        /** After a word: what makes it a node rather than a statement, a shape (`[`, `(`, …) or a link. */
        private const val MERMAID_NODE = """[\w\[({>-]|\s*+[<|}*o]{0,2}(?:--|==|-\.|\.\.|~~)"""

        /**
         * Mermaid diagrams (`.mmd`, ` ```mermaid `), which no lexer knows: line rules on plain text, earlier rules
         * winning, and the diagram type by [MermaidHighlighter].
         * - `%%` comments (only on lines of their own, as in Mermaid), `%%{init: …}%%` directives and the `---` lines
         *   around front matter (annotations);
         * - strings; statements (`subgraph`, `end`, `classDef`, `participant`, …) as keywords when they start a line
         *   and are not followed by a shape or link (then they are node names), a `direction` and a note's side;
         * - links of every diagram (`-->`, `-.->`, `==>`, `<|--`, `..|>`, `||--o{`, `-. text .->`, …) and the arrows of
         *   sequence diagrams (`->>`, `-)`, `-x`, `--x`) as operators, then `|labels|` as strings. Single-dash arrows
         *   need `>` or `)`, or for `-x` a following `name:`, so that hyphens in names stay plain; dots count only in
         *   pairs, so an ellipsis is no link.
         *
         * Known gaps: statements used as node names without a link on their line, and links inside node text
         * (`A[a -- b]`), are styled anyway.
         */
        val mermaid: SyntaxHighlighter =
            MermaidHighlighter(
                RSyntaxHighlighter(
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    lineRules =
                        listOf(
                            Regex("""^\s*(?<annotation>%%\{.*}%%)\s*$"""),
                            Regex("""^\s*(?<comment>%%.*)"""),
                            Regex("""^(?<annotation>---)\s*$"""),
                            Regex("""(?<string>"[^"\n]*+")"""),
                            Regex("""^\s*direction\s+(?<keyword>TB|TD|BT|RL|LR)\b"""),
                            Regex("""^\s*(?<keyword>$MERMAID_STATEMENTS)(?!$MERMAID_NODE)"""),
                            Regex("""^\s*[Nn]ote\s+(?<keyword>right of|left of|over)\b"""),
                            Regex("""(?<![-.=<])(?<operator>--?x)(?=\s*+\w++\s*+:)"""),
                            Regex(
                                """(?<operator>(?:<<|<\|?|[}|][|o]|(?<!\w)[ox*])?""" +
                                    """(?:--+|==+|-\.+-|(?<!\.)\.\.(?!\.)|~~~+)""" +
                                    """(?:>>|\|>|>|[|o][|{]|[ox](?!\w)|[)*])?)""",
                            ),
                            Regex("""(?<![-.])(?<operator>-\.)(?=\s)"""),
                            Regex("""(?<=\s)(?<operator>\.-+>|\.-+(?=\s))"""),
                            Regex("""(?<operator><<->>|-(?:>>?|\)))"""),
                            Regex("""(?<string>\|[^|\n]++\|)"""),
                        ),
                ),
            )

        /**
         * IntelliJ's cookie jar (`http-client.cookies`): tab-separated, with a `# domain path name value date` header
         * line, shown as a comment.
         */
        val cookies =
            RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_NONE, lineRules = listOf(Regex("""^(?<comment>#.*)""")))

        /**
         * `.env` files: the lexer reports keys as variables and unquoted values as identifiers, shown as property and
         * string. It starts a comment at every `#`, where dotenv does only after a space, so a `#` within an unquoted
         * value (`a#b`) stays part of it, and comments after it are found again.
         */
        val env =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_ENV,
                tokenStyles =
                    mapOf(
                        TokenTypes.VARIABLE to TokenStyle.PROPERTY,
                        TokenTypes.IDENTIFIER to TokenStyle.STRING,
                    ),
                keptStyles = setOf(TokenStyle.STRING),
                lineRules =
                    listOf(
                        Regex("""^[^=#'"]*=[^\s#'"]*(?<string>#\S*)"""),
                        Regex("""(?<=\s)(?<comment>#.*)"""),
                    ),
            )

        /** PEM files (certificates, keys): no lexer; `-----BEGIN …-----` and `-----END …-----` lines and headers. */
        val pem =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_NONE,
                lineRules =
                    listOf(
                        Regex("""^(?<keyword>-----(?:BEGIN|END) [^-]+-----)\s*$"""),
                        Regex("""^(?<property>[A-Za-z][\w-]*:)"""),
                    ),
            )
    }
}
