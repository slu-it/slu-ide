package dev.sluide.fileviewers

import dev.sluide.fileviewers.DiffKind.ADDED
import dev.sluide.fileviewers.DiffKind.MODIFIED
import dev.sluide.fileviewers.DiffKind.REMOVED
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffAlignmentTest {
    private fun row(
        original: Int?,
        current: Int?,
        kind: DiffKind? = null,
    ) = DiffRow(original, current, kind)

    @Test
    fun `unchanged lines are paired`() {
        assertEquals(listOf(row(0, 0), row(1, 1)), alignDiff(2, 2, emptyList()))
    }

    @Test
    fun `removed lines get filler rows on the right`() {
        // a b c d -> a d
        assertEquals(
            listOf(row(0, 0), row(1, null, REMOVED), row(2, null, REMOVED), row(3, 1)),
            alignDiff(4, 2, listOf(LineChange(1..2, 1 until 1))),
        )
    }

    @Test
    fun `added lines get filler rows on the left`() {
        // a b -> a x y b
        assertEquals(
            listOf(row(0, 0), row(null, 1, ADDED), row(null, 2, ADDED), row(1, 3)),
            alignDiff(2, 4, listOf(LineChange(1 until 1, 1..2))),
        )
    }

    @Test
    fun `the shorter side of a modification is padded`() {
        // a b c -> A B C D e (more on the right), then e f g -> e F (more on the left)
        assertEquals(
            listOf(row(0, 0, MODIFIED), row(null, 1, MODIFIED), row(1, 2)),
            alignDiff(2, 3, listOf(LineChange(0..0, 0..1))),
        )
        assertEquals(
            listOf(row(0, 0), row(1, 1, MODIFIED), row(2, null, MODIFIED)),
            alignDiff(3, 2, listOf(LineChange(1..2, 1..1))),
        )
    }

    @Test
    fun `changes at the start and the end, several in a row`() {
        // x a b c -> a b c y
        assertEquals(
            listOf(row(0, null, REMOVED), row(1, 0), row(2, 1), row(3, 2), row(null, 3, ADDED)),
            alignDiff(4, 4, listOf(LineChange(0..0, 0 until 0), LineChange(4 until 4, 3..3))),
        )
    }

    @Test
    fun `a diff older than the file is cut off and the rest shown unpaired`() {
        assertEquals(
            listOf(row(0, 0, MODIFIED), row(1, null), row(2, null)),
            alignDiff(3, 1, listOf(LineChange(0..0, 0..5))),
        )
    }

    @Test
    fun `lines are split at line breaks, a final one starts no line`() {
        assertEquals(emptyList(), textLines(""))
        assertEquals(listOf("a", "b"), textLines("a\nb\n"))
        assertEquals(listOf("a", "", "b"), textLines("a\r\n\r\nb"))
    }
}
