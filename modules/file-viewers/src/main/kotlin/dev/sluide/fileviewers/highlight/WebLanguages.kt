package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes

/**
 * The [RSyntaxHighlighter]s of web languages: JavaScript and TypeScript (also with JSX), JSON, XML, HTML, Vue and
 * Svelte components, and stylesheets (CSS, LESS, SCSS, Sass).
 */
internal object WebLanguages {
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
    val markupTags =
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
     * Handlebars and Mustache templates: HTML with `{{…}}` the lexer reads as plain text, partly as operators and
     * helper names. Line rules (so one mustache within one line) style, in this order:
     * - partials `{{> name}}` and `{{#> layout}}` (`keyword`, the name `type`);
     * - block helpers `{{#if}}`, `{{/if}}`, `{{#*inline}}` and `{{^inverse}}` as `keyword` (the lexer styles `else`);
     * - the delimiters `{{`, `{{{`, `}}`, `}}}` with their `~` as `operator`;
     * - `@data` variables (`annotation`) and `this` (`keyword`) inside a mustache;
     * - angle-bracket components (`<Ui::Card`, `</Ui::Card`: a capital right after `<`, then a blank, `/` or `>`) as
     *   `tag`, which the lexer takes for attributes.
     * Comments (`{{! }}`, `{{!-- --}}`) come from the lexer. Known gaps: a mustache over several lines; helper names
     * other than the lexer's (`lookup`, `log`) stay plain.
     */
    val handlebars =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_HANDLEBARS,
            lineRules =
                listOf(
                    Regex("""\{\{~?+(?<keyword>#?+>)\s*+(?<type>[\w./@-]++)?"""),
                    Regex("""\{\{~?+(?<keyword>[#/]\s*+\*?+[\w.@-]++|\^[\w.@-]*+)"""),
                    Regex("""(?<operator>\{\{\{?+~?+|~?+}}}?+)"""),
                    Regex("""(?<=\{\{[^}\n]{0,200})(?<annotation>@[A-Za-z_][\w.]*+)"""),
                    Regex("""(?<=\{\{[^}\n]{0,200})(?<![\w.@-])(?<keyword>this)(?![\w-])"""),
                    Regex("""(?<tag></?+[A-Z][\w.:-]*+)(?=[\s/>]|$)"""),
                ),
        )

    /**
     * JSP: the lexer styles Java in scriptlets and the name of a `<%@ %>` directive (`keyword`), but takes tag library
     * tags (`<c:forEach>`) for attributes and knows no `${…}`. Line rules make the directive (`<%@ page`)
     * `annotation`, taglib and `<jsp:…>` tags `tag` and EL expressions `${…}`/`#{…}` `annotation` (an unclosed
     * one runs to the line end), except inside attribute values, which stay strings. Known gap: an EL expression
     * over several lines.
     */
    val jsp =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_JSP,
            lineRules =
                listOf(
                    Regex("""(?<annotation><%@\s*+[\w.]++)"""),
                    Regex("""(?<tag></?[A-Za-z][\w-]*+:[\w.-]++)"""),
                    Regex("""(?<annotation>[$#]\{[^}\n]*+(?:}|$))"""),
                ),
        )

    /**
     * DTDs: the lexer styles `<!ELEMENT` and its parts as tag and attributes and does not know the rest of an
     * `<!ENTITY` at all, so its tag styles are replaced by line rules styled like an inline DTD in XML: quoted
     * values (also of `<!ENTITY`, `<!NOTATION`, `PUBLIC`, `SYSTEM`; references inside them stay part of the string)
     * as `string`, declarations and processing instructions as `annotation`, parameter and general entity references
     * outside quotes as `number`. Also `#PCDATA`, `#REQUIRED`, `#IMPLIED`, `#FIXED`, `EMPTY`/`ANY` after an element
     * name, and `SYSTEM`/`PUBLIC`/`NDATA` as `keyword`, and attribute types (`CDATA`, `ID`, `NMTOKENS`, …) as
     * `type` when a default or `#` follows. Known gaps: an attribute type at the end of a line, `EMPTY`/`ANY` on a
     * line after the element name, and a quoted value over several lines.
     */
    val dtd =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_DTD,
            tokenStyles =
                mapOf(
                    TokenTypes.MARKUP_TAG_DELIMITER to null,
                    TokenTypes.MARKUP_TAG_NAME to null,
                    TokenTypes.MARKUP_TAG_ATTRIBUTE to null,
                ),
            lineRules =
                listOf(
                    Regex("""(?<annotation><\?(?:[^?]++|\?(?!>))*+(?:\?>|$))"""),
                    Regex("""(?<string>"[^"\n]*+"|'[^'\n]*+')"""),
                    Regex("""(?<annotation><!(?:ELEMENT|ATTLIST|ENTITY|NOTATION)(?![\w-]))"""),
                    Regex("""(?<annotation><!\[\s*+(?:INCLUDE|IGNORE|%[\w.:-]++;)?+\s*+\[|]]>)"""),
                    Regex("""(?<keyword>#(?:PCDATA|REQUIRED|IMPLIED|FIXED))(?![\w-])"""),
                    Regex("""<!ELEMENT\s++[^\s>]++\s++(?<keyword>EMPTY|ANY)(?![\w-])"""),
                    Regex("""(?<=\s)(?<type>CDATA|ID|IDREFS?+|ENTITY|ENTITIES|NMTOKENS?+)(?=\s*+(?:[#"']|$))"""),
                    Regex("""(?<=\s)(?<keyword>SYSTEM|PUBLIC|NDATA)(?=\s)"""),
                    Regex("""(?<number>[%&]#?+[\w.:-]++;)"""),
                ),
        )

    /**
     * Apache `.htaccess`: the lexer knows only some directives (`php_value` or `SetOutputFilter` are plain) and no
     * variables. Line rules style the first word of a line as `builtin` (directives; sections like `<IfModule>`
     * stay `tag`, their arguments plain) and `%{HTTP_HOST}`, `%1`, `$1` and `${map:key}` as `annotation`;
     * quoted values as `string`; regex arguments stay plain. Known gaps: the continuation line of a directive ending
     * in `\` shows its first word as a directive; variables inside quotes stay strings.
     */
    val htaccess =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_HTACCESS,
            tokenStyles = mapOf(TokenTypes.MARKUP_TAG_ATTRIBUTE to null),
            lineRules =
                listOf(
                    Regex("""(?<string>(?<!\\)"(?:[^"\\\n]++|\\.)*+")"""),
                    Regex("""^\s*+(?<builtin>[A-Za-z_][\w-]*+)(?=\s|$)"""),
                    Regex("""(?<annotation>%\{[\w:.-]++}|\$\{[^}\n]*+(?:}|$)|[%$]\d)"""),
                ),
        )

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
}
