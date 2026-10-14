package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineOperationsTest {
    private fun duplicate(marked: String) = edited(marked, LineOperations::duplicate)

    private fun delete(marked: String) = edited(marked, LineOperations::deleteLines)

    private fun up(marked: String) = edited(marked, LineOperations::moveUp)

    private fun down(marked: String) = edited(marked, LineOperations::moveDown)

    @Test
    fun `duplicates the line of the caret below it`() {
        assertEquals("a\nbc\nb|c\nd", duplicate("a\nb|c\nd"))
        assertEquals("ab\nab|", duplicate("ab|"))
        assertEquals("\n|", duplicate("|"))
    }

    @Test
    fun `duplicates the selection after itself and selects the copy`() {
        assertEquals("abc^bc|d", duplicate("a^bc|d"))
        assertEquals("abc|bc^d", duplicate("a|bc^d"))
        assertEquals("a\nb\n^a\nb\n|", duplicate("^a\nb\n|"))
    }

    @Test
    fun `deletes the touched lines with their line break`() {
        assertEquals("a\nc|d", delete("a\nb|b\ncd"))
        assertEquals("a\nc|", delete("a\nbb|\nc"))
        assertEquals("d|", delete("a^b\nc|c\nd"))
        assertEquals("|d", delete("|ab\nc^c\nd"))
        assertEquals("|c", delete("^a\nb\n|c"))
    }

    @Test
    fun `deletes the last line with the line break before it`() {
        assertEquals("a|b", delete("ab\nc|d"))
        assertEquals("ab|", delete("ab\ncde|"))
        assertEquals("a\n|", delete("a\n\n|"))
        assertEquals("|", delete("a|b"))
    }

    @Test
    fun `does not delete in an empty text`() {
        assertNull(delete("|"))
    }

    @Test
    fun `moves lines up`() {
        assertEquals("b|b\na\nc", up("a\nb|b\nc"))
        assertEquals("^b\nc|\na\nd", up("a\n^b\nc|\nd"))
        assertEquals("^b\n|a\nc", up("a\n^b\n|c"))
    }

    @Test
    fun `moves lines down`() {
        assertEquals("b\na|a\nc", down("a|a\nb\nc"))
        assertEquals("c\n^a\nb|\n", down("^a\nb|\nc\n"))
        assertEquals("b\n^a\n|c", down("^a\n|b\nc"))
    }

    @Test
    fun `keeps the selection in the text when moving down onto the last line`() {
        assertEquals("b\n^a|", down("^a\n|b"))
        assertEquals("b\n|a^", down("|a\n^b"))
        assertEquals("\n^a\nb|", down("^a\nb\n|"))
    }

    @Test
    fun `does not move lines past the edges`() {
        assertNull(up("a|\nb"))
        assertNull(down("a\nb|"))
        assertNull(down("^a\nb|"))
    }
}
