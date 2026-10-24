package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes

/**
 * The [RSyntaxHighlighter]s of programming and scripting languages: Java, Kotlin, Groovy, Python, SQL, shell scripts and
 * PowerShell.
 */
internal object ScriptLanguages {
    val java = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_JAVA)

    /** The Kotlin lexer knows no raw strings and misses `package`. */
    val kotlin =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_KOTLIN,
            keywords = setOf("package"),
            rawStringDelimiter = "\"\"\"",
        )

    /** Groovy, also Gradle's Groovy DSL (`build.gradle`). */
    val groovy =
        RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_GROOVY, keywords = setOf("trait", "var", "record"))

    /** The Python lexer predates Python 3 in places; these corrections bring it up to date. */
    val python =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_PYTHON,
            keywords = setOf("async", "await", "nonlocal"),
            builtins = setOf("print", "exec"),
            stringPrefixes = setOf("f", "r", "b", "u", "rb", "br", "fr", "rf"),
        )

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
}
