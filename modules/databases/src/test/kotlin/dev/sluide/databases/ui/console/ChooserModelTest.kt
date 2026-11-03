package dev.sluide.databases.ui.console

import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import kotlin.test.Test
import kotlin.test.assertEquals

class ChooserModelTest {
    private val one = SqlStatement("select 1", 0, 8)
    private val two = SqlStatement("select 2\nfrom t", 10, 25)
    private val model = ChooserModel(RunChoice.Choose(two, listOf(one, two)))

    @Test
    fun `labels show the first line of the statement at the caret and the count`() {
        assertEquals(listOf("Statement at caret: select 2", "All statements (2)"), model.labels)
    }

    @Test
    fun `long first lines are cut at 80 characters`() {
        assertEquals("x".repeat(80) + "…", ChooserModel.firstLine("x".repeat(100)))
    }

    @Test
    fun `the statement at the caret is the default`() {
        assertEquals(0, model.defaultIndex)
        assertEquals(listOf(two), model.statements(model.defaultIndex))
        assertEquals(listOf(one, two), model.statements(1))
    }
}
