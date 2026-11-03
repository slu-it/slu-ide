package dev.sluide.databases.ui.console

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryFilterTest {
    private val entries = listOf("select * from Users", "delete from users where id = 1", "select 1")

    @Test
    fun `a blank query keeps everything`() {
        assertEquals(entries, HistoryFilter.filter(entries, "  "))
    }

    @Test
    fun `every word must match, ignoring case`() {
        assertEquals(entries.take(2), HistoryFilter.filter(entries, "USERS"))
        assertEquals(listOf(entries[1]), HistoryFilter.filter(entries, "from  where"))
        assertEquals(emptyList(), HistoryFilter.filter(entries, "users nothing"))
    }

    @Test
    fun `the label is the first line, cut with an ellipsis`() {
        assertEquals("select 1", HistoryFilter.label("select 1"))
        assertEquals("select *…", HistoryFilter.label("select *\nfrom t"))
        assertEquals("x".repeat(100) + "…", HistoryFilter.label("x".repeat(150)))
        assertEquals("x".repeat(100), HistoryFilter.label("x".repeat(100)))
        assertEquals("select 1", HistoryFilter.label("\n  select 1  "))
    }

    @Test
    fun `a statement replaces the selection`() {
        assertEquals(HistoryFilter.Edit(2, 5, "s"), HistoryFilter.insertion("ab cde f", 2, 5, 5, "s"))
    }

    @Test
    fun `it goes at the caret on a blank line and after a line with text`() {
        assertEquals(HistoryFilter.Edit(4, 4, "s"), HistoryFilter.insertion("abc\n\ndef", 4, 4, 4, "s"))
        assertEquals(HistoryFilter.Edit(0, 0, "s"), HistoryFilter.insertion("", 0, 0, 0, "s"))
        assertEquals(HistoryFilter.Edit(3, 3, "\ns"), HistoryFilter.insertion("abc\n\ndef", 1, 1, 1, "s"))
        assertEquals(HistoryFilter.Edit(3, 3, "\ns"), HistoryFilter.insertion("abc", 3, 3, 3, "s"))
    }
}
