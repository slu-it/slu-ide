package dev.sluide.httpclient

import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemSummaryTest {
    @Test
    fun `no problems have no text and no tooltip`() {
        assertEquals("" to null, problemSummary(emptyList()))
    }

    @Test
    fun `one problem is shown as it is`() {
        assertEquals("a" to "a", problemSummary(listOf("a")))
    }

    @Test
    fun `several problems show the first and how many more, the tooltip all of them`() {
        assertEquals("a (and 2 more)" to "a\nb\nc", problemSummary(listOf("a", "b", "c")))
    }
}
