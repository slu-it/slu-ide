package dev.sluide.games.doubles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardSizeTest {
    @Test
    fun `a side maps to its size`() {
        BoardSize.entries.forEach { assertEquals(it, BoardSize.ofSide(it.side)) }
    }

    @Test
    fun `other sides have no size`() {
        assertNull(BoardSize.ofSide(2))
        assertNull(BoardSize.ofSide(7))
        assertNull(BoardSize.ofSide(0))
    }

    @Test
    fun `goals, labels and default`() {
        assertEquals(listOf(512, 2048, 4096, 8192), BoardSize.entries.map { it.goal })
        assertEquals(listOf("3×3", "4×4", "5×5", "6×6"), BoardSize.entries.map { it.label })
        assertEquals(BoardSize.FOUR, BoardSize.DEFAULT)
    }
}
