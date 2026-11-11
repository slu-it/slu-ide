package dev.sluide.ebooks.view

import dev.sluide.ebooks.ChapterSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookSearchTest {
    private fun find(
        text: String,
        query: String,
        limit: Int = 1000,
    ) = BookSearch.find(text, listOf(ChapterSpan(0, "One", 0, text.length)), query, limit)

    @Test
    fun `finds case-insensitively and keeps the offsets of the text`() {
        val text = "The Quick brown fox, the QUICK one."
        val result = find(text, "quick")
        assertEquals(listOf(4 to 9, 25 to 30), result.matches.map { it.start to it.end })
        assertEquals(listOf("Quick", "QUICK"), result.matches.map { it.matchText })
    }

    @Test
    fun `offsets stay right after characters whose lower case has another length`() {
        val text = "İİİ ǅ needle"
        assertEquals(text.indexOf("needle"), find(text, "NEEDLE").matches.single().start)
    }

    @Test
    fun `a whitespace run in the query matches any whitespace run, a paragraph break included`() {
        val text = "end of one.\n\n  Start   of two"
        val m = find(text, "one.  start\tof").matches.single()
        assertEquals(text.indexOf("one."), m.start)
        assertEquals(text.indexOf(" two"), m.end)
        assertEquals("one. Start of", m.matchText)
    }

    @Test
    fun `leading and trailing whitespace of the query is ignored and a gap needs whitespace`() {
        assertEquals(1, find("a cat sat", "  cat  ").matches.size)
        assertEquals(0, find("catsat", "cat sat").matches.size)
    }

    @Test
    fun `a query of fewer than two non-blank characters finds nothing`() {
        assertTrue(find("a b a", "a").matches.isEmpty())
        assertTrue(find("a b a", "  a  ").matches.isEmpty())
        assertEquals(2, find("ab ab", "ab").matches.size)
        assertEquals(1, find("a b", "a b").matches.size)
    }

    @Test
    fun `snippets are cut at word boundaries with an ellipsis and show whitespace as one space`() {
        val before = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu "
        val after = " nu xi omicron pi rho sigma tau upsilon phi chi psi omega"
        val m = find(before + "TARGET\n\nword" + after, "target word").matches.single()
        assertEquals("TARGET word", m.matchText)
        assertEquals("… zeta eta theta iota kappa lambda mu ", m.snippetBefore)
        assertEquals(" nu xi omicron pi rho sigma tau upsilon…", m.snippetAfter)
    }

    @Test
    fun `no ellipsis where the chapter starts or ends`() {
        val m = find("short text here", "text").matches.single()
        assertEquals("short ", m.snippetBefore)
        assertEquals(" here", m.snippetAfter)
    }

    @Test
    fun `matches carry their chapter and do not cross chapters`() {
        val text = "alpha one|beta one two"
        val chapters = listOf(ChapterSpan(3, "First", 0, 9), ChapterSpan(5, "Second", 10, text.length))
        val result = BookSearch.find(text, chapters, "one")
        assertEquals(listOf(3 to "First", 5 to "Second"), result.matches.map { it.chapterIndex to it.chapterTitle })
        assertEquals(" two", result.matches[1].snippetAfter)
        assertTrue(BookSearch.find(text, chapters, "one|beta").matches.isEmpty())
    }

    @Test
    fun `stops at the limit and says so`() {
        val text = "word ".repeat(50)
        val result = find(text, "word", limit = 10)
        assertEquals(10, result.matches.size)
        assertTrue(result.capped)
        assertFalse(find(text, "word", limit = 50).capped)
    }

    @Test
    fun `a large text with many matches is searched quickly`() {
        val text = "lorem ipsum dolor sit amet consectetur adipiscing elit ".repeat(55_000)
        val chapters = listOf(ChapterSpan(0, "All", 0, text.length))
        val started = System.nanoTime()
        val result = BookSearch.find(text, chapters, "SIT   amet", limit = 2000)
        val none = BookSearch.find(text, chapters, "zzzz")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(2000, result.matches.size)
        assertTrue(none.matches.isEmpty())
        assertTrue(millis < 5000, "took $millis ms")
    }
}
