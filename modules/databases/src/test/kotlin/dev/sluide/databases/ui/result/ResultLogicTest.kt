package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.Page
import dev.sluide.databases.session.StatementOutcome
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal fun column(
    name: String,
    table: String? = "t",
    type: String = "text",
) = ColumnInfo(name, name, type, 12, null, table, true, false)

class ResultLogicTest {
    private val time = LocalTime.of(9, 5, 7)

    @Test
    fun `replace picks the first unpinned console tab and skips data tabs`() {
        assertNull(ResultTabsLogic.replaceIndex(emptyList()))
        assertNull(ResultTabsLogic.replaceIndex(listOf(TabInfo(pinned = true, data = false))))
        assertNull(ResultTabsLogic.replaceIndex(listOf(TabInfo(pinned = false, data = true))))
        val tabs =
            listOf(TabInfo(true, false), TabInfo(false, true), TabInfo(false, false), TabInfo(false, false))
        assertEquals(2, ResultTabsLogic.replaceIndex(tabs))
    }

    @Test
    fun `a tab with pending edits is not replaced`() {
        val tabs = listOf(TabInfo(false, false, pendingEdits = true), TabInfo(false, false))
        assertEquals(1, ResultTabsLogic.replaceIndex(tabs))
        assertNull(ResultTabsLogic.replaceIndex(listOf(TabInfo(false, false, pendingEdits = true))))
    }

    @Test
    fun `values are recognised as json or xml`() {
        assertEquals("json", ValueLanguage.of("""{"a": [1, 2]}"""))
        assertEquals("json", ValueLanguage.of("  [1,2] "))
        assertNull(ValueLanguage.of("{not json"))
        assertNull(ValueLanguage.of("""{"a":1} trailing"""))
        assertNull(ValueLanguage.of("12"))
        assertEquals("xml", ValueLanguage.of("<a><b/></a>"))
        assertNull(ValueLanguage.of("<a><b></a>"))
        assertNull(ValueLanguage.of("a < b"))
        // an external entity is neither resolved nor an error that escapes
        assertNull(ValueLanguage.of("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>"))
    }

    @Test
    fun `the selection rectangle gives columns and rows in the shown order`() {
        val cols = listOf(column("a"), column("b"), column("c"))
        val rows = listOf(listOf<Any?>(1, 2, 3), listOf<Any?>(4, 5, 6))
        val (c, r) = GridText.rectangle(cols, rows, listOf(1 to 0, 0 to 2), listOf(2, 1, 0))
        assertEquals(listOf("c", "a"), c.map { it.name })
        assertEquals(listOf(listOf<Any?>(3, 1), listOf<Any?>(6, 4)), r)
    }

    @Test
    fun `title is the single table else Result`() {
        assertEquals("users", ResultTabsLogic.title(listOf(column("a", "users"), column("b", "users"))))
        assertEquals("Result", ResultTabsLogic.title(listOf(column("a", "users"), column("b", "orders"))))
        assertEquals("Result", ResultTabsLogic.title(listOf(column("a", null))))
        assertEquals("Pin Tab", ResultTabsLogic.pinLabel(false))
        assertEquals("Unpin Tab", ResultTabsLogic.pinLabel(true))
    }

    @Test
    fun `status lists time and flags`() {
        assertEquals("23 ms", ResultTabsLogic.status(23, reExecuted = false, capped = false))
        assertEquals("2.1 s, re-executed, limit reached", ResultTabsLogic.status(2100, true, true))
    }

    @Test
    fun `header click orders ascending then toggles`() {
        val asc = ResultTabsLogic.nextOrderBy(DatabaseKind.POSTGRESQL, "", "id")
        assertEquals("\"id\" ASC", asc)
        assertEquals("\"id\" DESC", ResultTabsLogic.nextOrderBy(DatabaseKind.POSTGRESQL, asc, "id"))
        assertEquals("\"name\" ASC", ResultTabsLogic.nextOrderBy(DatabaseKind.POSTGRESQL, asc, "name"))
    }

    @Test
    fun `sortOf reads exactly the header-click forms`() {
        val cols = listOf(column("id"), column("name"))
        val kind = DatabaseKind.POSTGRESQL
        assertEquals(1 to false, ResultTabsLogic.sortOf(kind, " \"name\" DESC ", cols))
        assertEquals(0 to true, ResultTabsLogic.sortOf(kind, ResultTabsLogic.nextOrderBy(kind, "", "id"), cols))
        assertNull(ResultTabsLogic.sortOf(kind, "\"id\" ASC, \"name\"", cols))
        assertNull(ResultTabsLogic.sortOf(kind, "", cols))
    }

    @Test
    fun `page size field`() {
        assertEquals(500, ResultTabsLogic.parsePageSize(" ", 500))
        assertEquals(100, ResultTabsLogic.parsePageSize("100", 500))
        assertEquals(10, ResultTabsLogic.parsePageSize("1", 500))
        assertEquals(10_000, ResultTabsLogic.parsePageSize("99999", 500))
        assertNull(ResultTabsLogic.parsePageSize("abc", 500))
    }

    @Test
    fun `output lines per outcome`() {
        val cols = listOf(column("a"))
        val rows =
            StatementOutcome.Rows(
                "select\n  a from t",
                23,
                cols,
                FakeCursor(cols, emptyList()),
                Page(0, 0, listOf(listOf(1), listOf(2)), false),
            )
        assertEquals("09:05:07  select a from t  →  2 rows (more) in 23 ms", OutputText.line(time, rows).text)
        assertEquals(
            OutputText.line(time, StatementOutcome.Updated("update t", 5, 3)).text,
            "09:05:07  update t  →  3 rows updated in 5 ms",
        )
        assertEquals(
            "09:05:07  x  →  cancelled after 2.1 s",
            OutputText.line(time, StatementOutcome.Cancelled("x", 2100)).text,
        )
        val failed = OutputText.line(time, StatementOutcome.Failed("x", 1, "bad\nthing", "42", null))
        assertEquals("09:05:07  x  →  bad thing", failed.text)
        assertEquals(true, failed.error)
        assertEquals(OutputLine("09:05:07  Committed", false), OutputText.note(time, "Committed", false))
    }

    @Test
    fun `statement is one line cut at 200 characters`() {
        val cut = OutputText.statement("a".repeat(250))
        assertEquals(201, cut.length)
        assertEquals("…", cut.takeLast(1))
    }

    @Test
    fun `copy text is the rectangle over the selected cells`() {
        val cols = listOf(column("a"), column("b"), column("c"))
        val rows = listOf(listOf<Any?>(1, "x\ty", null), listOf<Any?>(2, "q", "z"), listOf<Any?>(3, "r", "w"))
        val cells = listOf(0 to 0, 1 to 2)
        assertEquals("1\t\n2\tz", GridText.copyText(cols, rows, cells, false))
        assertEquals("a\tc\n1\t\n2\tz", GridText.copyText(cols, rows, cells, true))
        assertEquals("\"x\ty\"", GridText.copyText(cols, rows, listOf(0 to 1), false))
        assertEquals("", GridText.copyText(cols, rows, emptyList(), true))
        // a moved column: c is shown before a
        assertEquals("c\ta\n\t1\nz\t2", GridText.copyText(cols, rows, cells, true, listOf(2, 1, 0)))
    }

    @Test
    fun `column width is limited`() {
        assertEquals(60.0, GridText.columnWidth("a", listOf("1")))
        assertEquals(400.0, GridText.columnWidth("a", listOf("x".repeat(500))))
    }
}
