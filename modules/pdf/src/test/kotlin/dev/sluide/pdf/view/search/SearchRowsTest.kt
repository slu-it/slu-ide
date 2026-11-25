package dev.sluide.pdf.view.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRowsTest {
    private fun hit(page: Int) = SearchHit(page, 0, 1, "", "x", "")

    private fun result(
        vararg pages: Int,
        capped: Boolean = false,
        noText: Boolean = false,
    ) = SearchResult(pages.map(::hit), capped, noText)

    @Test
    fun `rows group the hits under a heading per page, numbered from 1`() {
        val rows = searchRows(result(0, 0, 2))
        assertEquals(
            listOf("Page 1", "Hit 0", "Hit 0", "Page 3", "Hit 2"),
            rows.map {
                when (it) {
                    is SearchRow.Heading -> it.title
                    is SearchRow.Hit -> "Hit ${it.hit.page}"
                }
            },
        )
        assertEquals(emptyList(), searchRows(result()))
    }

    @Test
    fun `the status names the count, the cap, no matches and a PDF without text`() {
        assertEquals("No matches", searchStatus(result()))
        assertEquals("1 match", searchStatus(result(0)))
        assertEquals("41 matches", searchStatus(SearchResult(List(41) { hit(0) }, false, false)))
        assertEquals("1000+ matches", searchStatus(SearchResult(List(1000) { hit(0) }, true, false)))
        assertEquals("This PDF has no text.", searchStatus(result(noText = true)))
    }

    @Test
    fun `the status while collecting counts the page texts there`() {
        assertEquals("Searching… page 120 of 500", collectingStatus(120, 500))
    }
}
