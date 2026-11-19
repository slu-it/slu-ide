package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TypeScore
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rules that count: Markdown's kinds, SQL's statements, the weights of the keyword heuristics. */
class CountingRulesTest {
    @ParameterizedTest
    @MethodSource("markdown")
    fun `Markdown scores by the number of kinds of its syntax`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, MarkdownRule.scored(text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "- a\n- b", "1. a\n2) b", "[docs](docs/a.md)", "![logo](logo.png)", "| a | b |\n|---|:---:|",
            "**bold**", "> quoted", "Title\n=====", "---\ntitle: x\n---",
        ],
    )
    fun `each kind of Markdown counts as one before a heading`(kind: String) {
        assertEquals("md 60", MarkdownRule.scored("$kind\n\n# Heading\n"))
    }

    @ParameterizedTest
    @MethodSource("sql")
    fun `SQL counts statements at statement starts that are not sentences`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, SqlRule.scored(text))
    }

    @Test
    fun `a signal counts its weight once however often it is found`() {
        val signals = listOf(Signal("a", 10), Signal("b", 5), Signal("c", 1))
        assertEquals(15, weigh("a a a b", signals))
        assertEquals(0, weigh("xyz", signals))
    }

    @Test
    fun `a heuristic's score is capped at 90 and a weight of 0 is no score`() {
        val type = TextType("CSS", "css")
        assertEquals(emptyList(), heuristic(type, 0))
        assertEquals(listOf(TypeScore(type, 1)), heuristic(type, 1))
        assertEquals(listOf(TypeScore(type, 90)), heuristic(type, 90))
        assertEquals(listOf(TypeScore(type, 90)), heuristic(type, 91))
    }

    companion object {
        @JvmStatic
        fun markdown(): List<Arguments> =
            cases(
                "# Title\n# Other" to "",
                "- a\n- b" to "",
                "# Title\n\n- a\n- b" to "md 60",
                "# Title\n\n- a\n- [b](c.md)" to "md 80",
                "# Title\n\n- a\n- [b](c.md)\n\n**bold**" to "md 90",
                "Run it:\n\n```\nx\n```\n\nSee [docs](d.md)." to "md 90",
                "```\ncode\n```" to "",
                "```\n# Title\n- a" to "md 60",
            )

        @JvmStatic
        fun sql(): List<Arguments> =
            cases(
                "select a from t;" to "sql 80",
                "SELECT a FROM t" to "sql 80",
                "select * from t" to "sql 80",
                "select a from t where b = 1" to "sql 80",
                "select a from t" to "",
                "Select a from t" to "",
                "-- the report\n\nSELECT a FROM t" to "sql 80",
                "-- the report\nSELECT a FROM t" to "sql 80",
                "SELECT a\n-- the total\nFROM t" to "sql 80",
                "SELECT a FROM t\n\nSELECT b FROM u" to "sql 90",
                "SELECT a FROM t;\nSELECT b FROM u;" to "sql 90",
                "SELECT a FROM t\nSELECT b FROM u" to "sql 80",
                "x = 1\nSELECT a FROM t" to "",
                "INSERT INTO t VALUES (1)" to "sql 80",
                "UPDATE t SET a = 1" to "sql 80",
                "DELETE FROM t" to "sql 80",
                "CREATE TABLE t (a INT)" to "sql 80",
                "CREATE OR REPLACE VIEW v AS SELECT 1" to "sql 80",
                "CREATE UNIQUE INDEX i ON t (a)" to "sql 80",
                "ALTER TABLE t ADD b INT" to "sql 80",
                "WITH x AS (SELECT 1) SELECT * FROM x" to "sql 80",
                "Select the files from the list." to "",
                "Create table of contents." to "",
                "SELECTED items FROM the shop" to "",
                "select count(*) from orders" to "sql 80",
                "select t.* from t" to "sql 80",
                "select a from t where b like '*'" to "",
                "Delete from the drive anything where size > 1 GB." to "",
                "Select a seat from the plan where row = 5, please." to "",
                "SELECT a FROM t WHERE b = 1?" to "",
                "SELECT a FROM t WHERE b = 1!" to "",
            )
    }
}
