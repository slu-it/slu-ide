package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind.POSTGRESQL
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class StatementChoiceTest {
    private val text = "select 1;  select 2;\nselect 3"
    private val s1 = SqlStatement("select 1", 0, 8)
    private val s2 = SqlStatement("select 2", 11, 19)
    private val s3 = SqlStatement("select 3", 21, 29)

    private fun run(
        text: String,
        selection: IntRange? = null,
        caret: Int = 0,
    ) = StatementChoice.toRun(text, selection?.first ?: 0, selection?.let { it.last + 1 } ?: 0, caret, POSTGRESQL)

    @ParameterizedTest
    @CsvSource("0,1", "8,1", "9,1", "10,1", "11,2", "15,2", "19,2", "20,2", "21,3", "29,3")
    fun `caret picks the statement containing it or the nearest before`(
        caret: Int,
        expected: Int,
    ) {
        val choice = run(text, caret = caret) as RunChoice.Choose
        assertEquals(listOf(s1, s2, s3), choice.all)
        assertEquals(listOf(s1, s2, s3)[expected - 1], choice.atCaret)
    }

    @Test
    fun `caret after the last semicolon uses the nearest before`() {
        val choice = run("select 1; select 2; ", caret = 20) as RunChoice.Choose
        assertEquals("select 2", choice.atCaret.text)
    }

    @Test
    fun `caret before the first statement uses the first`() {
        val choice = run("  select 1; select 2", caret = 0) as RunChoice.Choose
        assertEquals("select 1", choice.atCaret.text)
    }

    @Test
    fun `no statements is None and one is Single`() {
        assertEquals(RunChoice.None, run(" ; -- c\n"))
        assertEquals(RunChoice.Single(SqlStatement("select 1", 1, 9)), run(" select 1; ", caret = 5))
    }

    @Test
    fun `a selection of one statement runs as selected`() {
        // "select 1;  select 2;" -> select the second statement with surrounding whitespace
        assertEquals(RunChoice.Single(s2), run(text, selection = 9..19, caret = 9))
    }

    @Test
    fun `a selection inside a statement runs the selected part`() {
        assertEquals(RunChoice.Single(SqlStatement("1", 7, 8)), run(text, selection = 7..7, caret = 8))
    }

    @Test
    fun `a selection of several statements is All with ranges in the whole text`() {
        assertEquals(RunChoice.All(listOf(s2, s3)), run(text, selection = 11..28, caret = 28))
    }

    @Test
    fun `an empty selection falls back to the caret and a blank one gives None`() {
        assertEquals(RunChoice.Single(SqlStatement("select 1", 0, 8)), run("select 1", selection = 3..2))
        assertEquals(RunChoice.None, run("select 1;   ", selection = 9..11))
        assertEquals(RunChoice.Single(SqlStatement("select 1", 0, 8)), run("select 1", selection = null))
    }
}
