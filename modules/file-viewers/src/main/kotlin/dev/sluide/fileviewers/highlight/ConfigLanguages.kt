package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes

/**
 * The [RSyntaxHighlighter]s of configuration and data files: YAML, Dockerfiles, Makefiles, INI-style files, ignore
 * files, `.git-blame-ignore-revs`, SSH configuration, Caddyfiles, cookie jars, `.env` and PEM files.
 */
internal object ConfigLanguages {
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

    /**
     * Java `.properties` files (`application.properties`, `gradle.properties`). The lexer reports keys as reserved
     * words and values as double-quoted strings, shown as keywords and strings: keys become properties, values stay
     * plain. It knows `#` and `!` comments and continued values (a line after one ending in `\`), and `=` and `:`
     * as separators (operators). `${placeholder}` is an annotation. Known gaps: with a blank as separator
     * (`key value`) the value is shown like a key, and an escaped separator in a key (`a\=b`) ends it.
     */
    val properties =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE,
            tokenStyles =
                mapOf(
                    TokenTypes.RESERVED_WORD to TokenStyle.PROPERTY,
                    TokenTypes.LITERAL_STRING_DOUBLE_QUOTE to null,
                    TokenTypes.VARIABLE to null,
                ),
            keptStyles = setOf(TokenStyle.COMMENT),
            lineRules = listOf(Regex("""(?<annotation>\$\{[^{}\s]*+})""")),
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
