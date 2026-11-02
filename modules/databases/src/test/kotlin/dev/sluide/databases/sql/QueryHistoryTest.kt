package dev.sluide.databases.sql

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class QueryHistoryTest {
    private val history = QueryHistory()

    @Test
    fun `newest first and a repeated statement moves to the top`() {
        history.add("a", "select 1")
        history.add("a", "select 2")
        history.add("a", " select 1 ")
        assertEquals(listOf("select 1", "select 2"), history.entries("a"))
    }

    @Test
    fun `keeps at most 200 and drops the oldest`() {
        repeat(250) { history.add("a", "select $it") }
        val entries = history.entries("a")
        assertEquals(200, entries.size)
        assertEquals("select 249", entries.first())
        assertEquals("select 50", entries.last())
    }

    @Test
    fun `keys are separate and blank statements ignored`() {
        history.add("a", "select 1")
        history.add("b", "select 2")
        history.add("b", "  ")
        assertEquals(listOf("select 1"), history.entries("a"))
        assertEquals(listOf("select 2"), history.entries("b"))
        assertEquals(emptyList(), history.entries("c"))
    }
}
