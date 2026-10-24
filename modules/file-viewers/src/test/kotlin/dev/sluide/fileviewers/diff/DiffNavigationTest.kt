package dev.sluide.fileviewers.diff

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.alignDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiffNavigationTest {
    // a b c d e -> a B c e x y: rows a, b/B, c, d/-, e, -/x, -/y
    private val rows =
        alignDiff(5, 6, listOf(LineChange(1..1, 1..1), LineChange(3..3, 3 until 3), LineChange(5 until 5, 4..5)))
    private val navigation = DiffNavigation(changeBlocks(rows))

    @Test
    fun `each run of changed rows is a change`() {
        assertEquals(listOf(1..1, 3..3, 5..6), changeBlocks(rows))
    }

    @Test
    fun `changes at the start and the end are found`() {
        val rows = alignDiff(2, 2, listOf(LineChange(0..0, 0..0), LineChange(1..1, 1..1)))
        assertEquals(listOf(0..1), changeBlocks(rows))
        assertEquals(listOf(0..0), changeBlocks(alignDiff(1, 0, listOf(LineChange(0..0, 0 until 0)))))
    }

    @Test
    fun `next is the first change after the row`() {
        assertEquals(1..1, navigation.next(0))
        assertEquals(3..3, navigation.next(1))
        assertEquals(5..6, navigation.next(4))
        assertNull(navigation.next(5))
    }

    @Test
    fun `previous is the last change before the row, also from within a change`() {
        assertNull(navigation.previous(1))
        assertEquals(1..1, navigation.previous(2))
        assertEquals(3..3, navigation.previous(5))
        assertEquals(3..3, navigation.previous(6))
        assertEquals(5..6, navigation.previous(7))
    }

    @Test
    fun `first, last and the change a row is in`() {
        assertEquals(1..1, navigation.first())
        assertEquals(5..6, navigation.last())
        assertEquals(2, navigation.indexAt(6))
        assertNull(navigation.indexAt(4))
    }

    @Test
    fun `without changes there is nowhere to go`() {
        val none = DiffNavigation(changeBlocks(alignDiff(2, 2, emptyList())))
        assertEquals(emptyList(), none.blocks)
        assertNull(none.first())
        assertNull(none.last())
        assertNull(none.next(0))
        assertNull(none.previous(1))
        assertNull(none.at(0))
    }

    @Test
    fun `the row of a line of the file`() {
        assertEquals(listOf(0, 1, 2, 4, 5, 6), (0..5).map { rowOfCurrent(rows, it) })
        assertEquals(7, rowOfCurrent(rows, 9), "beyond the end")
    }

    @Test
    fun `a marker on a line stands for its change, lines removed above it, or the next change`() {
        // Lines a B c e x y.
        assertEquals(1..1, navigation.at(rowOfCurrent(rows, 1)), "modified")
        assertEquals(3..3, navigation.at(rowOfCurrent(rows, 3)), "d removed above e")
        assertEquals(5..6, navigation.at(rowOfCurrent(rows, 5)), "added")
        assertEquals(1..1, navigation.at(rowOfCurrent(rows, 0)), "the next change")
        assertEquals(5..6, navigation.at(rowOfCurrent(rows, 9)), "the last change")
    }

    @Test
    fun `the counter tells the change the row is in, else how many changes there are`() {
        assertEquals("Change 1 of 3", navigation.counter(1))
        assertEquals("Change 3 of 3", navigation.counter(6))
        assertEquals("3 changes", navigation.counter(0))
        assertEquals("3 changes", navigation.counter(2))
    }

    @Test
    fun `the counter of one change and of none`() {
        val one = DiffNavigation(changeBlocks(alignDiff(2, 2, listOf(LineChange(1..1, 1..1)))))
        assertEquals("1 change", one.counter(0))
        assertEquals("Change 1 of 1", one.counter(1))
        assertEquals(true, one.hasChanges)

        val none = DiffNavigation(emptyList())
        assertEquals("No changes", none.counter(0))
        assertEquals(false, none.hasChanges)
    }
}
