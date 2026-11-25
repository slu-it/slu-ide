package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TextSelectionTest {
    /** Lines of 10 by 10 boxes, a line 20 points below the one before; spaces and line ends have no box, as extracted. */
    private fun horizontal(vararg lines: String): PageText {
        val boxes = mutableListOf<PageRect?>()
        lines.forEachIndexed { row, line ->
            if (row > 0) boxes += null
            line.forEachIndexed { column, char ->
                boxes += if (char == ' ') null else PageRect(column * 10.0, row * 20.0, 10.0, 10.0)
            }
        }
        return PageText(lines.joinToString("\n"), boxes)
    }

    /** The same on a page turned by 90 degrees: the lines run down, the next one 20 points to the right. */
    private fun vertical(vararg lines: String): PageText {
        val boxes = mutableListOf<PageRect?>()
        lines.forEachIndexed { column, line ->
            if (column > 0) boxes += null
            line.forEachIndexed { row, char ->
                boxes += if (char == ' ') null else PageRect(column * 20.0, row * 10.0, 10.0, 10.0)
            }
        }
        return PageText(lines.joinToString("\n"), boxes)
    }

    private val sample = horizontal("ab cd", "efghij")

    @Test
    fun `charAt gives the char whose box holds the point`() {
        assertEquals(3, TextSelection.charAt(sample, 35.0, 5.0))
        assertEquals(10, TextSelection.charAt(sample, 49.0, 29.0))
    }

    @Test
    fun `charAt between two lines gives the closer line`() {
        assertEquals(0, TextSelection.charAt(sample, 5.0, 12.0))
        assertEquals(6, TextSelection.charAt(sample, 5.0, 18.0))
    }

    @Test
    fun `charAt keeps to the closer line even when a char of the other is nearer`() {
        // Beyond the end of the short first line: its last char is 40 away, but the second line's, 9 below, is nearer.
        val text = horizontal("ab", "cdefgh")
        assertEquals(1, TextSelection.charAt(text, 60.0, 11.0))
        assertEquals(8, TextSelection.charAt(text, 60.0, 21.0))
    }

    @Test
    fun `charAt beyond a line's end and before its start gives its last and first char`() {
        assertEquals(4, TextSelection.charAt(sample, 500.0, 5.0))
        assertEquals(6, TextSelection.charAt(sample, -50.0, 25.0))
        assertEquals(0, TextSelection.charAt(sample, 5.0, -80.0))
        assertEquals(11, TextSelection.charAt(sample, 500.0, 500.0))
    }

    @Test
    fun `charAt in the gap between two words gives a char beside it`() {
        // The space has no box: the point is between b (10 to 20) and c (30 to 40), nearer c.
        assertEquals(3, TextSelection.charAt(sample, 27.0, 5.0))
        assertEquals(1, TextSelection.charAt(sample, 22.0, 5.0))
    }

    @Test
    fun `charAt on a page without boxes gives nothing`() {
        assertNull(TextSelection.charAt(PageText("", emptyList()), 1.0, 1.0))
        assertNull(TextSelection.charAt(PageText(" \n ", listOf(null, null, null)), 1.0, 1.0))
    }

    @Test
    fun `charAt on a turned page follows its lines, which run down`() {
        val text = vertical("abc", "def")
        // Between the two columns, nearer the first (a gap of 2 against 8).
        assertEquals(1, TextSelection.charAt(text, 12.0, 15.0))
        assertEquals(5, TextSelection.charAt(text, 18.0, 15.0))
        // Beyond the end of the second column.
        assertEquals(6, TextSelection.charAt(text, 28.0, 100.0))
        // Before the start of the first.
        assertEquals(0, TextSelection.charAt(text, 5.0, -40.0))
    }

    @Test
    fun `charAt on a turned page keeps to the closer line even when a char of the other is nearer`() {
        val text = vertical("ab", "cdefgh")
        assertEquals(1, TextSelection.charAt(text, 11.0, 60.0))
        assertEquals(8, TextSelection.charAt(text, 21.0, 60.0))
    }

    @Test
    fun `charAt of a single char line follows the direction of the page`() {
        val text = vertical("abc", "d", "efg")
        // The point is level with the second line's only char, closer to it than to the third column.
        assertEquals(4, TextSelection.charAt(text, 33.0, 5.0))
        val flat = horizontal("abc", "d", "efg")
        assertEquals(4, TextSelection.charAt(flat, 5.0, 33.0))
    }

    @Test
    fun `charUnder gives the char whose box contains the point, none in a gap or off the boxes`() {
        assertEquals(3, TextSelection.charUnder(sample, 35.0, 5.0))
        assertEquals(3, TextSelection.charUnder(sample, 30.0, 0.0))
        assertEquals(3, TextSelection.charUnder(sample, 40.0, 10.0))
        assertNull(TextSelection.charUnder(sample, 25.0, 5.0))
        assertNull(TextSelection.charUnder(sample, 35.0, 15.0))
        assertNull(TextSelection.charUnder(sample, 29.9, 5.0))
        assertNull(TextSelection.charUnder(sample, 50.1, 5.0))
        assertNull(TextSelection.charUnder(sample, 35.0, -0.1))
        assertNull(TextSelection.charUnder(sample, 35.0, 10.1))
    }

    private fun plain(text: String) = PageText(text, List(text.length) { null })

    @Test
    fun `wordAt takes letters, digits, underscores and apostrophes`() {
        val text = plain("say foo_bar9's, ok")
        assertEquals(4..13, TextSelection.wordAt(text, 4))
        assertEquals(4..13, TextSelection.wordAt(text, 8))
        assertEquals(4..13, TextSelection.wordAt(text, 13))
        assertEquals(0..2, TextSelection.wordAt(text, 1))
        assertEquals(16..17, TextSelection.wordAt(text, 17))
    }

    @Test
    fun `wordAt of a space or punctuation is that char alone`() {
        val text = plain("ab, cd")
        assertEquals(2..2, TextSelection.wordAt(text, 2))
        assertEquals(3..3, TextSelection.wordAt(text, 3))
        assertEquals(0..1, TextSelection.wordAt(text, 0))
        assertEquals(4..5, TextSelection.wordAt(text, 5))
    }

    @Test
    fun `wordAt stops at a line end and at the edges of the text`() {
        val text = plain("ab\ncd")
        assertEquals(0..1, TextSelection.wordAt(text, 1))
        assertEquals(2..2, TextSelection.wordAt(text, 2))
        assertEquals(3..4, TextSelection.wordAt(text, 4))
    }

    @Test
    fun `between puts the earlier char first, whichever way it was drawn`() {
        val forwards = TextSelection(1, 4, 2, 7)
        assertEquals(forwards, TextSelection.between(TextPosition(1, 4), TextPosition(2, 7)))
        assertEquals(forwards, TextSelection.between(TextPosition(2, 7), TextPosition(1, 4)))
        assertEquals(TextSelection(3, 2, 3, 6), TextSelection.between(TextPosition(3, 6), TextPosition(3, 2)))
        assertEquals(TextSelection(3, 2, 3, 6), TextSelection.between(TextPosition(3, 2), TextPosition(3, 6)))
        assertEquals(TextSelection(3, 2, 3, 2), TextSelection.between(TextPosition(3, 2), TextPosition(3, 2)))
        assertEquals(TextSelection(1, 9, 2, 1), TextSelection.between(TextPosition(2, 1), TextPosition(1, 9)))
    }

    @Test
    fun `all reaches from the first char of the first page to the end of the last`() {
        val all = TextSelection.all(4)
        assertEquals(0..9, all.rangeOn(0, 10))
        assertEquals(0..9, all.rangeOn(4, 10))
        assertNull(all.rangeOn(5, 10))
    }

    @Test
    fun `rangeOn gives the part of the selection on a page`() {
        val selection = TextSelection(1, 5, 3, 2)
        assertNull(selection.rangeOn(0, 10))
        assertEquals(5..9, selection.rangeOn(1, 10))
        assertEquals(0..9, selection.rangeOn(2, 10))
        assertEquals(0..2, selection.rangeOn(3, 10))
        assertNull(selection.rangeOn(4, 10))
    }

    @Test
    fun `rangeOn clips to the page's length and gives nothing past its end or on an empty page`() {
        val selection = TextSelection(1, 5, 3, 2)
        assertNull(selection.rangeOn(1, 5))
        assertEquals(5..5, selection.rangeOn(1, 6))
        assertNull(selection.rangeOn(2, 0))
        assertEquals(0..1, selection.rangeOn(3, 2))
        assertEquals(0..0, selection.rangeOn(3, 1))
        assertNull(selection.rangeOn(3, 0))
        assertEquals(2..4, TextSelection(1, 2, 1, 4).rangeOn(1, 20))
        assertEquals(2..3, TextSelection(1, 2, 1, 40).rangeOn(1, 4))
    }

    @Test
    fun `copyText joins the pages with blank lines and trims the trailing spaces of each line`() {
        val pages = listOf("Hello  \nworld  ", "Second page", "Third")
        val selection = TextSelection(0, 2, 2, 2)
        assertEquals("llo\nworld\n\nSecond page\n\nThi", selection.copyText(pages::get))
    }

    @Test
    fun `copyText of one page keeps its line ends and leading spaces`() {
        assertEquals("  b\nc", TextSelection(0, 1, 0, 5).copyText { "a  b\nc d" })
    }

    @Test
    fun `copyText leaves out pages without selected text`() {
        val pages = listOf("a", "", "c")
        assertEquals("a\n\nc", TextSelection.all(2).copyText(pages::get))
        assertEquals("", TextSelection.all(1).copyText { "" })
        assertTrue(TextSelection(0, 5, 0, 7).copyText { "abc" }.isEmpty())
    }
}
