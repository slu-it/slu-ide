package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.DatabaseKind.MARIADB
import dev.sluide.databases.connect.DatabaseKind.POSTGRESQL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import kotlin.test.assertEquals

/**
 * The quoting and comment rules per server, from the documentation (no servers in the sandbox):
 * - PostgreSQL, "Lexical Structure" (sql-syntax-lexical): 4.1.2.1 string constants, `''` is a quote and a backslash
 *   is an ordinary character; 4.1.2.2 `E'..'` strings take backslash escapes; 4.1.2.4 dollar quoting, the tag
 *   follows the rules of an unquoted identifier (no leading digit, no `$`), is case sensitive, and a dollar-quoted
 *   string that follows an identifier must be separated from it by whitespace; 4.1.5 `--` comments and nested
 *   `/* */` comments; `"` quotes an identifier, a backtick and `#` are not quoting.
 * - MariaDB, "Comment Syntax" and "String Literals" (kb): `'` and `"` open strings with backslash escapes (unless
 *   NO_BACKSLASH_ESCAPES / ANSI_QUOTES), a backtick quotes an identifier without escapes, `#` and `-- ` (the dashes
 *   followed by whitespace or a control character) are line comments, `/* */` comments do not nest, and
 *   `/*! */`, `/*M! */` hold code.
 */
class SqlLexerTest {
    private fun regions(
        sql: String,
        kind: DatabaseKind = POSTGRESQL,
    ) = SqlLexer.regions(sql, kind)

    private fun str(
        start: Int,
        end: Int,
        closed: Boolean = true,
    ) = Region(RegionType.STRING, start, end, closed)

    private fun ident(
        start: Int,
        end: Int,
        closed: Boolean = true,
    ) = Region(RegionType.IDENTIFIER, start, end, closed)

    private fun comment(
        start: Int,
        end: Int,
        closed: Boolean = false,
    ) = Region(RegionType.COMMENT, start, end, closed)

    @Test
    fun `plain text has no regions`() {
        assertEquals(emptyList(), regions("select a, b from t where x - -1 > 2 / 3 * 4"))
        assertEquals(emptyList(), regions(""))
    }

    @Test
    fun `a string ends at its closing quote, a doubled quote stays inside`() {
        assertEquals(listOf(str(7, 13)), regions("select 'a''b' from t"))
        assertEquals(listOf(str(0, 3)), regions("'a'"), "a quote as the last character")
        assertEquals(listOf(str(0, 4, closed = false)), regions("'abc"))
        assertEquals(listOf(str(0, 4, closed = false)), regions("'a''"), "the doubled quote does not close it")
        assertEquals(listOf(str(0, 1, closed = false)), regions("'"))
    }

    @Test
    fun `in PostgreSQL a backslash is an ordinary character in a string`() {
        assertEquals(listOf(str(7, 11), str(15, 18)), regions("select 'a\\' || 'b'"))
    }

    @Test
    fun `in PostgreSQL a quoted identifier is an identifier and a backtick is code`() {
        assertEquals(listOf(ident(7, 13), str(15, 18)), regions("select \"a\"\"b\", 'x'"))
        assertEquals(listOf(ident(0, 4, closed = false)), regions("\"abc"))
        assertEquals(emptyList(), regions("select `a;b`"))
        assertEquals(listOf(ident(0, 4)), regions("\"a\\\" b"), "a backslash does not escape in an identifier")
    }

    @Test
    fun `in PostgreSQL an E string takes backslash escapes`() {
        assertEquals(listOf(str(8, 14)), regions("select E'a\\'b'"))
        assertEquals(listOf(str(8, 14)), regions("select e'a\\'b'"))
        assertEquals(listOf(str(1, 6)), regions("e'\\'x'"), "the E is the second character")
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "Z", "7", "_", "$"])
    fun `an e behind an identifier character does not make an E string`(prefix: String) {
        // the lexer's rule (isIdentChar counts digits and `$`); PostgreSQL itself reads `7e'..` as 7 then e
        val sql = "${prefix}e'a\\'b'"
        // `'a\'` closes at the backslash-quote, then b and a lone quote
        assertEquals(listOf(str(2, 6), str(7, 8, closed = false)), regions(sql), sql)
    }

    @ParameterizedTest
    @ValueSource(strings = [" ", "(", ",", "=", "-"])
    fun `an e behind another character makes an E string`(prefix: String) {
        val sql = "x$prefix" + "e'a\\'b'"
        assertEquals(listOf(str(3, 9)), regions(sql), sql)
    }

    @Test
    fun `in MariaDB both quotes open strings with backslash escapes`() {
        assertEquals(listOf(str(7, 13)), regions("select 'a\\'b'", MARIADB))
        assertEquals(listOf(str(0, 5)), regions("'a\\\\'", MARIADB), "an escaped backslash")
        assertEquals(listOf(str(7, 13)), regions("select \"a\\\"b\"", MARIADB))
        assertEquals(listOf(str(0, 7)), regions("'it''s'", MARIADB))
        assertEquals(listOf(str(0, 5, closed = false)), regions("'abc\\", MARIADB), "an escape at the end")
        assertEquals(listOf(str(0, 4, closed = false)), regions("\"abc", MARIADB))
    }

    @Test
    fun `in MariaDB a backtick quotes an identifier without escapes`() {
        assertEquals(listOf(ident(7, 11)), regions("select `a\\` x", MARIADB))
        assertEquals(listOf(ident(0, 6)), regions("`a``b`", MARIADB))
        assertEquals(listOf(ident(0, 4, closed = false)), regions("`abc", MARIADB))
    }

    @Test
    fun `a line comment ends before the line break and is never closed`() {
        assertEquals(listOf(comment(9, 13)), regions("select 1 -- a\nfrom"))
        assertEquals(listOf(comment(9, 12)), regions("select 1 --x"))
        assertEquals(listOf(comment(0, 2)), regions("--"))
        assertEquals(listOf(comment(0, 3), comment(4, 7)), regions("-- \n-- "))
        assertEquals(emptyList(), regions("select 1 - -1"))
        assertEquals(emptyList(), regions("select 1 #x"), "# is no comment in PostgreSQL")
    }

    @Test
    fun `in MariaDB dashes start a comment only before whitespace, a control character or the end`() {
        assertEquals(emptyList(), regions("select 1 --a", MARIADB))
        assertEquals(listOf(comment(9, 11)), regions("select 1 --", MARIADB))
        assertEquals(listOf(comment(9, 13)), regions("select 1 -- a", MARIADB))
        assertEquals(listOf(comment(9, 13)), regions("select 1 --\ta", MARIADB))
        assertEquals(listOf(comment(9, 15)), regions("select 1 --\u0001abc", MARIADB))
        assertEquals(listOf(comment(9, 11)), regions("select 1 --\nx", MARIADB))
        assertEquals(emptyList(), regions("select 1 -\u0001-", MARIADB))
    }

    @Test
    fun `in MariaDB a hash starts a line comment`() {
        assertEquals(listOf(comment(9, 11)), regions("select 1 #x\nfrom", MARIADB))
        assertEquals(listOf(comment(0, 1)), regions("#", MARIADB))
    }

    @Test
    fun `block comments nest in PostgreSQL and do not in MariaDB`() {
        val sql = "a /* b /* c */ d */ e"
        assertEquals(listOf(comment(2, 19, closed = true)), regions(sql, POSTGRESQL))
        assertEquals(listOf(comment(2, 14, closed = true)), regions(sql, MARIADB))
    }

    @Test
    fun `a block comment needs its closing, and the slash of its opener does not close it`() {
        assertEquals(listOf(comment(2, 6)), regions("x /* y"))
        assertEquals(listOf(comment(0, 8)), regions("/* /* */"), "one level is still open")
        assertEquals(listOf(comment(0, 3)), regions("/*/", POSTGRESQL))
        assertEquals(listOf(comment(0, 4, closed = true)), regions("/**/"))
        assertEquals(listOf(comment(0, 6, closed = true)), regions("/*/ */"))
        assertEquals(listOf(comment(0, 5, closed = true)), regions("/*x*/y", MARIADB))
        assertEquals(listOf(comment(0, 2)), regions("/*"))
        assertEquals(listOf(comment(0, 3)), regions("/* "))
        assertEquals(listOf(comment(1, 3)), regions("//*"))
    }

    @Test
    fun `MariaDB executable comments hold code, their opener ends before the code`() {
        assertEquals(emptyList(), regions("/*!40101 select 1 */", MARIADB))
        assertEquals(emptyList(), regions("/*M!100100 select 1 */", MARIADB))
        assertEquals(emptyList(), regions("/*! select 1 */", MARIADB))
        assertEquals(listOf(str(9, 14)), regions("/*!40101 'a;b' */", MARIADB))
        assertEquals(listOf(comment(0, 8)), regions("/*!40101", POSTGRESQL))
        assertEquals(listOf(comment(0, 7, closed = true)), regions("/* x */", MARIADB))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/*!", "/*M!", "/*!4", "/*!40101", "/*M!100100", "/*!40101 x", "/*M!5 x"])
    fun `an executable comment opener ends after its version digits`(text: String) {
        val digits = text.substringBefore(' ')
        assertEquals(digits.length, SqlLexer.executableCommentEnd(text, 0), text)
        assertEquals(1 + digits.length, SqlLexer.executableCommentEnd("x$text", 1), "behind another character")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/* x", "/*M x", "/*", "/*m!1", "/**!1", "/x!1", "x", ""])
    fun `a comment that is no executable comment has no opener`(text: String) {
        assertEquals(0, SqlLexer.executableCommentEnd(text, 0), text)
    }

    @Test
    fun `dollar quotes end at their own tag`() {
        assertEquals(listOf(str(7, 14)), regions("select \$\$a;b\$\$ x"))
        assertEquals(listOf(str(0, 4)), regions("\$\$\$\$"), "the closing tag may follow at once")
        assertEquals(listOf(str(0, 6)), regions("\$a\$\$a\$"))
        assertEquals(listOf(str(0, 5), str(6, 11)), regions("\$\$x\$\$ \$\$y\$\$"))
        val tagged = "do \$t\$ x \$\$ y \$t\$ z"
        assertEquals(listOf(str(3, tagged.lastIndexOf("\$t\$") + 3)), regions(tagged))
    }

    @Test
    fun `a dollar quote may have letters, digits behind the first, and underscores in its tag`() {
        assertEquals(listOf(str(0, 9)), regions("\$a1\$x\$a1\$"))
        assertEquals(listOf(str(0, 7)), regions("\$_\$x\$_\$"))
        assertEquals(listOf(str(0, 7)), regions("\$é\$x\$é\$"))
    }

    @Test
    fun `an unterminated dollar quote runs to the end, tags are case sensitive`() {
        assertEquals(listOf(str(0, 6, closed = false)), regions("\$\$ abc"))
        assertEquals(listOf(str(0, 9, closed = false)), regions("\$tag\$ abc"))
        assertEquals(listOf(str(0, 15, closed = false)), regions("\$tag\$ abc \$TAG\$"))
        assertEquals(listOf(str(0, 4, closed = false)), regions("\$\$ \$"), "a lone dollar at the end is inside")
    }

    @Test
    fun `a dollar that opens no quote is code`() {
        assertEquals(emptyList(), regions("select \$1 + \$2"))
        assertEquals(emptyList(), regions("\$"))
        assertEquals(emptyList(), regions("\$a"))
        assertEquals(emptyList(), regions("\$a b\$"))
        assertEquals(emptyList(), regions("\$1\$x\$1\$"), "the tag cannot start with a digit")
        assertEquals(emptyList(), regions("\$a-b\$x\$a-b\$"))
        assertEquals(emptyList(), regions("select \$\$a;b\$\$", MARIADB))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "Z", "7", "_"])
    fun `a dollar quote cannot follow an identifier character`(prefix: String) {
        // `a` and `_` are the documented rule (PostgreSQL 4.1.2.4); `7` is the lexer's simplification (isIdentChar)
        val sql = "$prefix\$\$x\$\$"
        assertEquals(emptyList(), regions(sql), sql)
    }

    @Test
    fun `a dollar quote right behind another one is not opened`() {
        // two literals side by side are a syntax error in PostgreSQL anyway
        assertEquals(listOf(str(0, 5)), regions("\$\$x\$\$\$\$y\$\$"))
    }

    @ParameterizedTest
    @ValueSource(strings = [" ", "(", ",", "=", ";"])
    fun `a dollar quote may follow another character`(prefix: String) {
        val sql = "$prefix\$\$x\$\$"
        assertEquals(listOf(str(1, 6)), regions(sql), sql)
    }

    @Test
    fun `regions of different types follow each other in order`() {
        val sql = "select 'a' -- c\n, \"d\" /* e */ \$\$f\$\$"
        assertEquals(
            listOf(
                str(7, 10),
                comment(11, 15),
                ident(18, 21),
                comment(22, 29, closed = true),
                str(30, 35),
            ),
            regions(sql),
        )
    }

    @Test
    fun `hostile text of any length is read in linear time`() {
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            val n = 200_000
            assertEquals(listOf(str(0, n, closed = false)), regions("'".padEnd(n, 'x')))
            assertEquals(listOf(comment(0, 2 * n)), regions("/*".repeat(n)))
            assertEquals(listOf(str(0, 5 * n, closed = false)), regions("\$a\$".padEnd(5 * n, ' ')))
            assertEquals(emptyList(), regions("\$a ".repeat(n)))
            assertEquals(n, regions("'x' ".repeat(n)).size)
            assertEquals(n, regions("--x\n".repeat(n), POSTGRESQL).size)
            assertEquals(listOf(str(0, n, closed = false)), regions("'" + "\\".repeat(n - 1), MARIADB))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["POSTGRESQL", "MARIADB"])
    fun `a quote two characters before a bang is a string in both servers`(kind: String) {
        assertEquals(listOf(str(1, 7)), regions("a'!x;y'", DatabaseKind.valueOf(kind)))
        assertEquals(listOf(str(1, 7)), regions("/'!a;b'", DatabaseKind.valueOf(kind)))
    }

    @Test
    fun `in PostgreSQL a bang comment is an ordinary block comment`() {
        assertEquals(listOf(comment(0, 13, closed = true)), regions("/*!40101 x */"))
        assertEquals(listOf(comment(0, 15, closed = true)), regions("/*M!100100 x */"))
    }

    @Test
    fun `a PostgreSQL string behind a character that is no e takes no backslash escapes`() {
        assertEquals(listOf(str(4, 8), str(12, 15)), regions("x = 'a\\' || 'b'"))
    }
}
