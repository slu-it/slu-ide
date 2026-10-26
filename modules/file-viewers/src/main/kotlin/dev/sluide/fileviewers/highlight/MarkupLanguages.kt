package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes

/**
 * The highlighters of Markdown, MDX and Mermaid, and [forLanguage], which picks a code fence's highlighter among
 * all languages.
 */
internal object MarkupLanguages {
    /**
     * Markdown: headings and bold as keywords, emphasis as type, inline HTML as markup with component names made
     * tags by [WebLanguages.markupTags]. The lexer reports inline and fenced code as preprocessor, shown as string
     * here, and a link's or image's text and target as regex and annotation, both shown as `attribute`. Fenced code
     * with a language that [forLanguage] knows is highlighted as that language ([FencedCodeHighlighter]).
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
                lineRules = WebLanguages.markupTags + lineRules,
            ),
            ::forLanguage,
        )

    val markdown = markdown(lineRules = emptyList())

    /** MDX: Markdown with `import`/`export` lines and JSX components. */
    val mdx = markdown(lineRules = listOf(Regex("""^(?<keyword>import|export)\b""")))

    /** `\verb|text|`: the delimiter is any character but a letter, `*` or blank; unclosed, the text ends the line. */
    private const val VERB = """\\verb\*?+(?<d>[^A-Za-z*\s])(?:(?!\k<d>).)*+(?:\k<d>|$)"""

    /** `$…$` and `$$…$$` math; a `$` escaped as `\$` neither starts nor ends it. */
    private const val DOLLAR_MATH = """(?<!\\)\$\$?+(?:[^$\\]++|\\.)*+\$\$?+"""

    /**
     * LaTeX: the lexer takes the `%` in `\verb|a % b|` for a comment (a mask hides `\verb` text from it), shows
     * commands as `builtin` (made `keyword`, also `\@ifundefined` and `\int_0`) and the names in `\begin{…}` as
     * keywords. Line rules style, earlier ones winning: `\verb` text and math `$…$`, `$$…$$` as `string` (one
     * rule, so a `$` in `\verb` starts no math), environment names (`type`), `\(…\)` and `\[…\]` (`string`; an
     * unclosed one runs to the line end, `\\[2pt]` is no math), then commands. `\%` is no comment. Known gaps: math
     * over several lines, and the content of `verbatim` environments (a `%` there starts a comment).
     */
    val latex =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_LATEX,
            tokenStyles = mapOf(TokenTypes.FUNCTION to TokenStyle.KEYWORD),
            lexerMasks = listOf(Regex(VERB)),
            lineRules =
                listOf(
                    Regex("""(?<string>$VERB|$DOLLAR_MATH)"""),
                    Regex("""\\(?:begin|end)\s*+\{(?<type>[^}\\\n]++)}"""),
                    Regex("""(?<string>(?<!\\)\\\((?:[^\\]++|\\(?!\)))*+(?:\\\)|$))"""),
                    Regex("""(?<string>(?<!\\)\\\[(?:[^\\]++|\\(?!]))*+(?:\\]|$))"""),
                    Regex("""(?<keyword>\\[A-Za-z@]++)"""),
                ),
        )

    /** A Protocol Buffers string, `"…"` with escapes, on one line; the lexer runs one to the last `"` of a line. */
    private const val PROTO_STRING = """(?<!\\)"(?:[^"\\\n]++|\\.)*+""""

    /**
     * Protocol Buffers: the lexer takes everything from the first to the last `"` of a line for one string, so
     * strings are masked from it and styled by the first line rule (a comment after them stays a comment). Further
     * line rules add the contextual words it misses, so that fields named like them stay plain: `edition = "2023"`,
     * `map<…>` (`type`), `stream` in an rpc's parentheses, `extensions` and `group Name = 1 {`. Known gaps: a field
     * named like a keyword (`string service = 1;`, `string to = 2;`, `max`) shows that keyword (the lexer's); a lone
     * `"` in a block comment, followed on the same line by its closing marker and a string, keeps the comment open
     * until the next closing marker; single-quoted strings are not masked, so the lexer merges `'x']; // 'y'`.
     */
    val protobuf =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_PROTO,
            lexerMasks = listOf(Regex(PROTO_STRING)),
            lineRules =
                listOf(
                    Regex("""(?<string>$PROTO_STRING)"""),
                    Regex("""^\s*+(?<keyword>edition)(?=\s*+=)"""),
                    Regex("""(?<![\w.])(?<type>map)(?=\s*+<)"""),
                    Regex("""\(\s*+(?<keyword>stream)\s++(?=[.\w])"""),
                    Regex("""^\s*+(?<keyword>extensions)(?=\s++\d)"""),
                    Regex("""^\s*+(?:(?:optional|required|repeated)\s++)?(?<keyword>group)(?=\s++\w++\s*+=)"""),
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

    /** The highlighter for a code fence's [language] (` ```ts `), lower case, or `null` if there is none. */
    fun forLanguage(language: String): SyntaxHighlighter? =
        when (language) {
            "java" -> ScriptLanguages.java
            "c" -> CompiledLanguages.c
            "cpp", "c++", "cxx", "h", "hpp" -> CompiledLanguages.cpp
            "csharp", "cs", "c#" -> CompiledLanguages.csharp
            "go", "golang" -> CompiledLanguages.go
            "gomod", "go.mod", "go.work" -> CompiledLanguages.goMod
            "rust", "rs" -> CompiledLanguages.rust
            "d" -> CompiledLanguages.d
            "scala" -> CompiledLanguages.scala
            "dart" -> CompiledLanguages.dart
            "vb", "vbnet", "vbscript" -> CompiledLanguages.visualBasic
            "pascal", "delphi", "pas" -> CompiledLanguages.pascal
            "fortran", "f90" -> CompiledLanguages.fortran
            "asm", "nasm", "x86asm" -> CompiledLanguages.assembler
            "vhdl" -> CompiledLanguages.vhdl
            "kotlin", "kt", "kts" -> ScriptLanguages.kotlin
            "groovy", "gradle" -> ScriptLanguages.groovy
            "python", "py" -> ScriptLanguages.python
            "ruby", "rb" -> DynamicLanguages.ruby
            "php" -> DynamicLanguages.php
            "perl", "pl" -> DynamicLanguages.perl
            "lua" -> DynamicLanguages.lua
            "clojure", "clj", "edn" -> DynamicLanguages.clojure
            "lisp", "elisp", "emacs-lisp", "scheme", "racket" -> DynamicLanguages.lisp
            "tcl" -> DynamicLanguages.tcl
            "batch", "bat", "cmd", "batchfile", "dosbatch" -> DynamicLanguages.batch
            "javascript", "js", "mjs", "cjs" -> WebLanguages.javaScript
            "jsx" -> WebLanguages.javaScriptReact
            "typescript", "ts" -> WebLanguages.typeScript
            "tsx" -> WebLanguages.typeScriptReact
            "json", "jsonc" -> WebLanguages.json
            "xml", "svg" -> WebLanguages.xml
            "html" -> WebLanguages.html
            "protobuf", "proto" -> protobuf
            "latex", "tex" -> latex
            "handlebars", "hbs", "mustache" -> WebLanguages.handlebars
            "jsp" -> WebLanguages.jsp
            "dtd" -> WebLanguages.dtd
            "htaccess", "apacheconf" -> WebLanguages.htaccess
            "vue", "svelte" -> WebLanguages.singleFileComponent
            "yaml", "yml" -> ConfigLanguages.yaml
            "css" -> WebLanguages.css
            "scss" -> WebLanguages.scss
            "sass" -> WebLanguages.sass
            "less" -> WebLanguages.less
            "markdown", "md" -> markdown
            "mdx" -> mdx
            "sql" -> ScriptLanguages.sql
            "kql", "kusto", "csl" -> KqlHighlighter
            "graphql", "gql" -> GraphQlHighlighter
            "bash", "sh", "shell", "zsh", "ksh" -> ScriptLanguages.shell
            "powershell", "ps1", "pwsh" -> ScriptLanguages.powerShell
            "dockerfile", "docker", "containerfile" -> ConfigLanguages.dockerfile
            "makefile", "make", "mk" -> ConfigLanguages.makefile
            "ini", "cfg", "conf", "editorconfig", "gitconfig" -> ConfigLanguages.ini
            "properties" -> ConfigLanguages.properties
            "json5", "jsonl", "ndjson" -> WebLanguages.json
            "codeowners" -> ConfigLanguages.codeowners
            "gitattributes" -> ConfigLanguages.gitattributes
            "mailmap" -> ConfigLanguages.mailmap
            "gitignore" -> ConfigLanguages.ignoreFile
            "ssh-config", "sshconfig", "ssh_config", "sshd_config" -> ConfigLanguages.sshConfig
            "caddyfile", "caddy" -> ConfigLanguages.caddyfile
            "mermaid", "mmd" -> mermaid
            "bicep", "bicepparam" -> BicepHighlighter
            "hcl", "terraform", "tf", "tfvars" -> HclHighlighter
            "dotenv", "env" -> ConfigLanguages.env
            "toml" -> TomlHighlighter
            else -> null
        }
}
