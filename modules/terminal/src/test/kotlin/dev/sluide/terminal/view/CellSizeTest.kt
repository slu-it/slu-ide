package dev.sluide.terminal.view

import com.jediterm.core.util.TermSize
import kotlin.test.Test
import kotlin.test.assertEquals

class CellSizeTest {
    private val cell = CellSize(width = 10.0, height = 20.0, baseline = 15.0)

    @Test
    fun `fits whole cells into the area`() {
        assertEquals(TermSize(80, 24), cell.gridSize(805.0, 489.0))
    }

    @Test
    fun `is at least 5 columns and 2 rows`() {
        assertEquals(TermSize(5, 2), cell.gridSize(0.0, 0.0))
        assertEquals(TermSize(5, 24), cell.gridSize(30.0, 480.0))
        assertEquals(TermSize(80, 2), cell.gridSize(800.0, 30.0))
    }
}
