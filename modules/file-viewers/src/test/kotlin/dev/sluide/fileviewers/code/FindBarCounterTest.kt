package dev.sluide.fileviewers.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindBarCounterTest {
    private fun text(
        count: Int,
        truncated: Boolean = false,
        current: Int? = null,
        error: String? = null,
        query: String = "q",
    ) = counterText(count, truncated, current, error, query)

    @Test
    fun `an error wins over everything`() {
        assertEquals("bad regex", text(3, current = 0, error = "bad regex"))
        assertEquals("bad regex", text(0, error = "bad regex", query = ""))
    }

    @Test
    fun `an empty query says nothing`() {
        assertEquals("", text(0, query = ""))
    }

    @Test
    fun `no match says no results`() {
        assertEquals("No results", text(0))
    }

    @Test
    fun `the current match is told with its number`() {
        assertEquals("1 of 1", text(1, current = 0))
        assertEquals("3 of 7", text(7, current = 2))
        assertEquals("2 of 10000+", text(10000, truncated = true, current = 1))
    }

    @Test
    fun `without a current match the results are counted`() {
        assertEquals("1 result", text(1))
        assertEquals("2 results", text(2))
        assertEquals("1+ results", text(1, truncated = true))
        assertEquals("10000+ results", text(10000, truncated = true))
    }

    @Test
    fun `the counter is an error for an error or no results of a query`() {
        assertTrue(counterIsError(0, "bad", "q"))
        assertTrue(counterIsError(2, "bad", "q"))
        assertTrue(counterIsError(0, null, "q"))
        assertFalse(counterIsError(0, null, ""))
        assertFalse(counterIsError(1, null, "q"))
    }

    @Test
    fun `replacements are counted`() {
        assertEquals("1 replaced", replacedText(1))
        assertEquals("4 replaced", replacedText(4))
    }
}
