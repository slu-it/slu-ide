package dev.sluide.fileviewers

import dev.sluide.fileviewers.LineMarker.ADDED
import dev.sluide.fileviewers.LineMarker.MODIFIED
import dev.sluide.fileviewers.LineMarker.REMOVED_ABOVE
import kotlin.test.Test
import kotlin.test.assertEquals

class LineMarkersTest {
    @Test
    fun `added and modified lines are marked as such`() {
        // a b c -> a B c x y
        val markers = LineMarkers.of(listOf(LineChange(1..1, 1..1), LineChange(3 until 3, 3..4)), 5)
        assertEquals(listOf(null, MODIFIED, null, ADDED, ADDED), markers)
    }

    @Test
    fun `removed lines mark the line after them`() {
        // a b c d -> a d
        assertEquals(listOf(null, REMOVED_ABOVE), LineMarkers.of(listOf(LineChange(1..2, 1 until 1)), 2))
    }

    @Test
    fun `removed lines at the end mark the last line`() {
        // a b c -> a
        assertEquals(listOf(REMOVED_ABOVE), LineMarkers.of(listOf(LineChange(1..2, 1 until 1)), 1))
    }

    @Test
    fun `a removal does not hide the marker of a changed line after it`() {
        val changes = listOf(LineChange(1..1, 1 until 1), LineChange(3 until 3, 1..1))
        assertEquals(listOf(null, ADDED, null), LineMarkers.of(changes, 3))
    }

    @Test
    fun `unchanged files and empty files have no markers`() {
        assertEquals(listOf(null, null), LineMarkers.of(emptyList(), 2))
        assertEquals(emptyList(), LineMarkers.of(listOf(LineChange(0..1, 0 until 0)), 0))
    }

    @Test
    fun `changes beyond the lines are cut off`() {
        assertEquals(listOf(null, ADDED), LineMarkers.of(listOf(LineChange(1 until 1, 1..5)), 2))
    }
}
