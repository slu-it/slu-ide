package dev.sluide.pdf.view.search

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfSearchTest {
    private fun find(
        vararg pages: String,
        query: String,
        cap: Int = PdfSearch.CAP,
    ) = PdfSearch.find(pages.toList(), query, cap)

    private fun SearchResult.only(): SearchHit {
        assertEquals(1, hits.size, hits.toString())
        return hits[0]
    }

    @Test
    fun `finds case-insensitively and keeps the offsets of the text`() {
        val result = find("The Quick brown fox, the QUICK one.", query = "quick")
        assertEquals(listOf(4 to 9, 25 to 30), result.hits.map { it.start to it.end })
        assertEquals(listOf("Quick", "QUICK"), result.hits.map { it.match })
    }

    @Test
    fun `offsets stay right after characters whose lower case has another length`() {
        val text = "İİİ ǅ needle"
        assertEquals(text.indexOf("needle"), find(text, query = "NEEDLE").only().start)
    }

    @Test
    fun `a whitespace run in the query matches any whitespace run, a line end included`() {
        val text = "end of one.\n  Start   of two\n"
        val hit = find(text, query = "one.  start\tof two").only()
        assertEquals(text.indexOf("one."), hit.start)
        assertEquals(text.indexOf("two") + 3, hit.end)
        assertEquals("one. Start of two", hit.match)
    }

    @Test
    fun `leading and trailing whitespace of the query is ignored and a gap needs whitespace`() {
        assertEquals(1, find("a cat sat", query = "  cat  ").hits.size)
        assertEquals(0, find("catsat", query = "cat sat").hits.size)
    }

    @Test
    fun `a query of fewer than two non-blank characters finds nothing`() {
        assertFalse(PdfSearch.searchable("a"))
        assertFalse(PdfSearch.searchable(" \n a  "))
        assertTrue(PdfSearch.searchable("ab"))
        assertTrue(PdfSearch.searchable("a b"))
        assertTrue(find("a b a", query = "  a  ").hits.isEmpty())
        assertEquals(2, find("ab ab", query = "ab").hits.size)
        assertEquals(1, find("a b", query = "a b").hits.size)
    }

    @Test
    fun `snippets are cut at word boundaries with an ellipsis and show whitespace as one space`() {
        val before = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu "
        val after = " nu xi omicron pi rho sigma tau upsilon phi chi psi omega"
        val hit = find(before + "TARGET\n\nword" + after, query = "target word").only()
        assertEquals("TARGET word", hit.match)
        assertEquals("… zeta eta theta iota kappa lambda mu ", hit.before)
        assertEquals(" nu xi omicron pi rho sigma tau upsilon…", hit.after)
    }

    @Test
    fun `a snippet cut inside a word without a space before the match keeps the cut`() {
        val word = "x".repeat(50)
        val hit = find("${word}needle$word", query = "needle").only()
        assertEquals("… " + "x".repeat(40), hit.before)
        assertEquals("x".repeat(40) + "…", hit.after)
    }

    @Test
    fun `a snippet edge on a word gap keeps the whole word next to it`() {
        // 40 chars before the match start with "first", right after a space; 40 after it end with "two" before a space.
        val before = "first" + " two".repeat(8) + "   "
        val after = " two".repeat(10)
        val hit = find("lead ${before}needle$after tail", query = "needle").only()
        assertEquals("… first" + " two".repeat(8) + " ", hit.before)
        assertEquals(" two".repeat(10) + "…", hit.after)
    }

    @Test
    fun `a word cut by the snippet's right edge is left out`() {
        val hit = find("needle" + " two".repeat(9) + " three more", query = "needle").only()
        assertEquals(" two".repeat(9) + "…", hit.after)
    }

    @Test
    fun `whitespace at the page's start and end is left out of the snippets`() {
        val hit = find(" \n needle here\n", query = "needle").only()
        assertEquals("" to " here", hit.before to hit.after)
    }

    @Test
    fun `a text ending in the start of the query is no match`() {
        assertEquals(listOf(0), find("needle and need", query = "needle").hits.map { it.start })
    }

    @Test
    fun `no ellipsis where the page text starts or ends`() {
        val hit = find("short text here", query = "text").only()
        assertEquals("short ", hit.before)
        assertEquals(" here", hit.after)
        val atEdges = find("needle", query = "needle").only()
        assertEquals("" to "", atEdges.before to atEdges.after)
    }

    @Test
    fun `several hits on one page and hits on several pages come in page order`() {
        val result = find("one needle, two needle", "", "no hit here", "needle at the start", query = "needle")
        assertEquals(listOf(0 to 4, 0 to 16, 3 to 0), result.hits.map { it.page to it.start })
        assertFalse(result.noText)
    }

    @Test
    fun `a match does not cross pages and a snippet stays on its page`() {
        val result = find("first page ends with needle", "in a haystack", query = "needle in")
        assertTrue(result.hits.isEmpty())
        val hit = find("before", "needle", "after", query = "needle").only()
        assertEquals("" to "", hit.before to hit.after)
    }

    @Test
    fun `pages that are all blank have no text`() {
        assertTrue(find("\n", "", "  \n", query = "needle").noText)
        assertFalse(find("\n", "x", query = "needle").noText)
        assertTrue(find(query = "needle").noText)
    }

    @Test
    fun `stops at the cap and says so`() {
        val text = "word ".repeat(30)
        val result = find(text, text, query = "word", cap = 40)
        assertEquals(40, result.hits.size)
        assertTrue(result.capped)
        assertEquals(1, result.hits.last().page)
        assertFalse(find(text, text, query = "word", cap = 60).capped)
        assertEquals(PdfSearch.CAP, find("word ".repeat(1001), query = "word").hits.size)
    }

    @Test
    fun `the time grows with the pages, not faster`() {
        val page = "lorem ipsum dolor sit amet consectetur adipiscing elit\n".repeat(60)
        val threads = ManagementFactory.getThreadMXBean()

        /** CPU time of this thread (not wall-clock time, docs/testing.md "Time limits") for [pages] pages. */
        fun cpuTime(pages: Int): Long {
            val texts = List(pages) { page }
            val started = threads.currentThreadCpuTime
            assertEquals(pages * 60, PdfSearch.find(texts, "SIT   amet", cap = Int.MAX_VALUE).hits.size)
            assertTrue(PdfSearch.find(texts, "zzzz").hits.isEmpty())
            return threads.currentThreadCpuTime - started
        }
        cpuTime(300)
        val small = cpuTime(500)
        val large = cpuTime(1000)
        assertTrue(large < small * 3, "500 pages: ${small / 1_000_000} ms, 1000 pages: ${large / 1_000_000} ms")
    }
}
