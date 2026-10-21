package dev.sluide.editorarea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabOverflowTest {
    private val fiveTabs = TabOverflow(List(5) { 100.0 }, 250.0)

    @Test
    fun `tabs that fit do not overflow and cannot scroll`() {
        val overflow = TabOverflow(listOf(100.0, 150.0), 250.0)

        assertFalse(overflow.overflows)
        assertEquals(0, overflow.maxFirst)
        assertEquals(0..1, overflow.visible(0))
        assertEquals(emptyList(), overflow.hidden(0))
    }

    @Test
    fun `only completely visible tabs count as visible`() {
        assertTrue(fiveTabs.overflows)
        assertEquals(0..1, fiveTabs.visible(0))
        assertEquals(listOf(2, 3, 4), fiveTabs.hidden(0))
        assertEquals(listOf(0, 3, 4), fiveTabs.hidden(1))
    }

    @Test
    fun `scrolling stops once the last tab is visible`() {
        assertEquals(3, fiveTabs.maxFirst)
        assertEquals(3, fiveTabs.clamp(7))
        assertEquals(0, fiveTabs.clamp(-1))
        assertEquals(300.0, fiveTabs.offset(3))
    }

    @Test
    fun `revealing scrolls as little as possible`() {
        assertEquals(0, fiveTabs.reveal(1, 0), "already visible")
        assertEquals(2, fiveTabs.reveal(3, 0), "to the right: tab becomes the rightmost visible one")
        assertEquals(1, fiveTabs.reveal(1, 3), "to the left: tab becomes the leftmost visible one")
    }

    @Test
    fun `a tab wider than the viewport is scrolled to the left edge`() {
        val overflow = TabOverflow(listOf(100.0, 400.0, 100.0), 250.0)

        assertEquals(1, overflow.reveal(1, 0))
        assertEquals(2, overflow.maxFirst)
    }

    @Test
    fun `a dragged tab passes a neighbour once its edge is beyond the neighbour's centre`() {
        val equal = TabOverflow(List(3) { 100.0 }, 250.0)

        assertEquals(0, equal.dropIndex(0, 49.0))
        assertEquals(1, equal.dropIndex(0, 51.0), "right edge beyond the centre of the tab at 100..200")
        assertEquals(2, equal.dropIndex(0, 151.0))
        assertEquals(2, equal.dropIndex(1, 151.0))
        assertEquals(1, equal.dropIndex(2, 149.0), "left edge before the centre of the tab at 100..200")
        assertEquals(0, equal.dropIndex(2, 49.0))
    }

    @Test
    fun `dropping between tabs of different widths, both directions`() {
        val overflow = TabOverflow(listOf(100.0, 200.0, 50.0), 250.0)

        assertEquals(0, overflow.dropIndex(0, 99.0))
        assertEquals(1, overflow.dropIndex(0, 101.0), "right edge beyond the centre of the 200 wide tab")
        assertEquals(2, overflow.dropIndex(0, 226.0), "right edge beyond the centre of the last tab")
        assertEquals(2, overflow.dropIndex(2, 201.0))
        assertEquals(1, overflow.dropIndex(2, 199.0), "left edge before the centre of the 200 wide tab")
        assertEquals(0, overflow.dropIndex(2, 49.0))
    }

    @Test
    fun `dragging beyond either end keeps the tab at that end`() {
        assertEquals(0, fiveTabs.dropIndex(3, -500.0))
        assertEquals(4, fiveTabs.dropIndex(1, 5000.0))
        assertEquals(0, TabOverflow(listOf(100.0), 250.0).dropIndex(0, 5000.0))
    }

    @Test
    fun `no tabs`() {
        val overflow = TabOverflow(emptyList(), 250.0)

        assertFalse(overflow.overflows)
        assertEquals(0, overflow.clamp(3))
        assertEquals(emptyList(), overflow.hidden(0))
    }
}
