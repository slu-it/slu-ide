package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlHighlightingTest {
    private val sql =
        Highlighted(
            """
            |-- users older than 18
            |/* over
            |   two lines */
            |CREATE TABLE IF NOT EXISTS users (id BIGSERIAL PRIMARY KEY, name VARCHAR(20), data jsonb DEFAULT '{}');
            |SELECT u.id, COUNT(*), coalesce(u.name, 'none') FROM users u WHERE u.age > 18 AND u.active = TRUE
            |ORDER BY 1 DESC LIMIT 10 OFFSET 2.5;
            |insert into users (name) values ('it''s') returning id;
            |
            """.trimMargin(),
            RSyntaxHighlighter.sql,
        )

    @Test
    fun `keywords in upper and lower case, including those the lexer misses`() {
        for (keyword in listOf("CREATE", "SELECT", "WHERE", "IF", "DEFAULT", "LIMIT", "OFFSET", "TRUE", "insert")) {
            assertEquals(setOf(TokenStyle.KEYWORD), sql.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), sql.stylesOf("returning"))
    }

    @Test
    fun `data types and functions`() {
        for (type in listOf("BIGSERIAL", "VARCHAR", "jsonb")) {
            assertEquals(setOf(TokenStyle.TYPE), sql.stylesOf(type), type)
        }
        assertEquals(setOf(TokenStyle.BUILTIN), sql.stylesOf("COUNT"))
        assertEquals(setOf(TokenStyle.BUILTIN), sql.stylesOf("coalesce"))
    }

    @Test
    fun `comments, strings, numbers and qualified names`() {
        assertEquals(setOf(TokenStyle.COMMENT), sql.stylesOf("-- users older than 18"))
        assertEquals(setOf(TokenStyle.COMMENT), sql.stylesOf("two lines */"))
        assertEquals(setOf(TokenStyle.STRING), sql.stylesOf("'it''s'"))
        assertEquals(setOf(TokenStyle.NUMBER), sql.stylesOf("18", occurrence = 1))
        assertEquals(setOf(TokenStyle.NUMBER), sql.stylesOf("2.5"))
        assertEquals(setOf(TokenStyle.OPERATOR), sql.stylesOf("."), "the dot of u.id is no number")
        assertEquals(emptySet(), sql.stylesOf("users", occurrence = 1))
    }

    @Test
    fun `spans are well-formed`() = sql.assertWellFormed()
}
