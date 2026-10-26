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
            "kotlin", "kt", "kts" -> ScriptLanguages.kotlin
            "groovy", "gradle" -> ScriptLanguages.groovy
            "python", "py" -> ScriptLanguages.python
            "javascript", "js", "mjs", "cjs" -> WebLanguages.javaScript
            "jsx" -> WebLanguages.javaScriptReact
            "typescript", "ts" -> WebLanguages.typeScript
            "tsx" -> WebLanguages.typeScriptReact
            "json", "jsonc" -> WebLanguages.json
            "xml", "svg" -> WebLanguages.xml
            "html" -> WebLanguages.html
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
            "dotenv", "env" -> ConfigLanguages.env
            "toml" -> TomlHighlighter
            else -> null
        }
}
