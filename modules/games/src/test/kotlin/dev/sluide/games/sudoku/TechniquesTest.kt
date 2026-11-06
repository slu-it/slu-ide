package dev.sluide.games.sudoku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-built candidate states: every cell not listed is filled, "125" lists the candidates 1, 2 and 5. */
private fun state(
    size: SudokuSize,
    vararg cells: Pair<Cell, String>,
) = Candidates.of(size, cells.associate { (cell, digits) -> cell to digits.map { it - '0' }.toSet() })

private fun nineState(vararg cells: Pair<Cell, String>) = state(SudokuSize.NINE, *cells)

private fun at(
    r: Int,
    c: Int,
) = Cell(r, c)

private fun Candidates.at(
    r: Int,
    c: Int,
): Set<Int> = candidateSet(Cell(r, c))

class TechniquesTest {
    @Test
    fun `naked single`() {
        val s = nineState(at(0, 0) to "5", at(0, 1) to "12")
        val p = assertNotNull(s.nakedSingle())
        assertEquals(0, p.index)
        assertEquals(5, p.digit)
        assertNull(nineState(at(0, 0) to "12", at(0, 1) to "12").nakedSingle())
    }

    @Test
    fun `hidden single`() {
        val s = nineState(at(0, 0) to "12", at(0, 1) to "23", at(0, 2) to "23")
        val p = assertNotNull(s.hiddenSingle())
        assertEquals(0, p.index)
        assertEquals(1, p.digit)
        val none = nineState(at(0, 0) to "12", at(0, 1) to "12", at(1, 0) to "12", at(1, 1) to "12")
        assertNull(none.hiddenSingle())
    }

    @Test
    fun `pointing removes the digit from the rest of the row`() {
        val s = nineState(at(0, 0) to "57", at(0, 1) to "58", at(0, 5) to "56")
        assertTrue(s.pointing())
        assertEquals(setOf(6), s.at(0, 5))
        assertEquals(setOf(5, 7), s.at(0, 0))
        assertEquals(setOf(5, 8), s.at(0, 1))
    }

    @Test
    fun `pointing does not fire when the digit is not confined to a line`() {
        val s = nineState(at(0, 0) to "57", at(0, 1) to "58", at(1, 1) to "59", at(0, 5) to "56")
        assertFalse(s.pointing())
        assertEquals(setOf(5, 6), s.at(0, 5))
    }

    @Test
    fun `pointing in 6x6 with 2x3 boxes works for rows and columns`() {
        val row = state(SudokuSize.SIX, at(0, 0) to "34", at(0, 1) to "35", at(0, 4) to "34")
        assertTrue(row.pointing())
        assertEquals(setOf(4), row.at(0, 4))
        // column 2 holds the box's right-hand cells (0,2) and (1,2); (4,2) is outside the box
        val col = state(SudokuSize.SIX, at(0, 2) to "36", at(1, 2) to "35", at(4, 2) to "35")
        assertTrue(col.pointing())
        assertEquals(setOf(5), col.at(4, 2))
    }

    @Test
    fun `claiming removes the digit from the rest of the box`() {
        val s = nineState(at(0, 0) to "24", at(0, 1) to "25", at(1, 2) to "26")
        assertTrue(s.claiming())
        // the 2s of row 0 left of column 3 all sit in box 0
        assertEquals(setOf(6), s.at(1, 2))
        assertEquals(setOf(2, 4), s.at(0, 0))
    }

    @Test
    fun `claiming does not fire when the line spans two boxes`() {
        val s = nineState(at(0, 0) to "24", at(0, 4) to "25", at(1, 2) to "26")
        assertFalse(s.claiming())
        assertEquals(setOf(2, 6), s.at(1, 2))
    }

    @Test
    fun `claiming in 6x6 with 2x3 boxes`() {
        val s = state(SudokuSize.SIX, at(0, 0) to "24", at(0, 1) to "25", at(1, 2) to "26", at(1, 3) to "26")
        assertTrue(s.claiming())
        assertEquals(setOf(6), s.at(1, 2))
        assertEquals(setOf(2, 6), s.at(1, 3))
    }

    @Test
    fun `naked pair`() {
        val s = nineState(at(0, 0) to "12", at(0, 1) to "12", at(0, 2) to "123", at(0, 3) to "24")
        assertTrue(s.nakedSubset(2))
        assertEquals(setOf(3), s.at(0, 2))
        assertEquals(setOf(4), s.at(0, 3))
        assertEquals(setOf(1, 2), s.at(0, 0))
        assertFalse(nineState(at(0, 0) to "12", at(0, 1) to "13", at(0, 2) to "245").nakedSubset(2))
    }

    @Test
    fun `hidden pair`() {
        val s = nineState(at(0, 0) to "123", at(0, 1) to "124", at(0, 2) to "345", at(0, 3) to "345")
        assertTrue(s.hiddenSubset(2))
        assertEquals(setOf(1, 2), s.at(0, 0))
        assertEquals(setOf(1, 2), s.at(0, 1))
        assertEquals(setOf(3, 4, 5), s.at(0, 2))
        assertEquals(setOf(3, 4, 5), s.at(0, 3))
        val none = nineState(at(0, 0) to "123", at(0, 1) to "124", at(0, 2) to "134", at(0, 3) to "234")
        assertFalse(none.hiddenSubset(2))
    }

    @Test
    fun `naked triple`() {
        val s = nineState(at(0, 0) to "12", at(0, 1) to "23", at(0, 2) to "13", at(0, 3) to "1234")
        assertTrue(s.nakedSubset(3))
        assertEquals(setOf(4), s.at(0, 3))
        val none = nineState(at(0, 0) to "12", at(0, 1) to "23", at(0, 2) to "14", at(0, 3) to "12345")
        assertFalse(none.nakedSubset(3))
    }

    @Test
    fun `hidden triple`() {
        val s =
            nineState(
                at(0, 0) to "127",
                at(0, 1) to "238",
                at(0, 2) to "139",
                at(0, 3) to "457",
                at(0, 4) to "458",
                at(0, 5) to "459",
            )
        assertTrue(s.hiddenSubset(3))
        assertEquals(setOf(1, 2), s.at(0, 0))
        assertEquals(setOf(2, 3), s.at(0, 1))
        assertEquals(setOf(1, 3), s.at(0, 2))
        assertEquals(setOf(4, 5, 7), s.at(0, 3))
        val none =
            nineState(
                at(0, 0) to "127",
                at(0, 1) to "238",
                at(0, 2) to "139",
                at(0, 3) to "1457",
                at(0, 4) to "458",
                at(0, 5) to "459",
                // the extra cell keeps 1, 2 and 3 from forming a hidden triple inside the box, too
                at(1, 0) to "12",
            )
        assertFalse(none.hiddenSubset(3))
    }

    @Test
    fun `x-wing`() {
        val s =
            nineState(
                at(1, 2) to "41",
                at(1, 6) to "42",
                at(5, 2) to "43",
                at(5, 6) to "45",
                at(3, 2) to "47",
                at(8, 6) to "49",
            )
        assertTrue(s.fish(2))
        assertEquals(setOf(7), s.at(3, 2))
        assertEquals(setOf(9), s.at(8, 6))
        assertEquals(setOf(1, 4), s.at(1, 2))
        val none =
            nineState(
                at(1, 2) to "41",
                at(1, 6) to "42",
                at(5, 2) to "43",
                at(5, 6) to "45",
                at(5, 7) to "46",
                at(3, 2) to "47",
            )
        assertFalse(none.fish(2))
        assertEquals(setOf(4, 7), none.at(3, 2))
    }

    @Test
    fun `swordfish`() {
        val s =
            nineState(
                at(0, 1) to "51",
                at(0, 4) to "52",
                at(3, 4) to "53",
                at(3, 7) to "54",
                at(6, 1) to "56",
                at(6, 7) to "59",
                at(2, 1) to "58",
            )
        assertTrue(s.fish(3))
        assertEquals(setOf(8), s.at(2, 1))
        assertEquals(setOf(1, 5), s.at(0, 1))
        val none =
            nineState(
                at(0, 1) to "51",
                at(0, 4) to "52",
                at(3, 0) to "57",
                at(3, 4) to "53",
                at(3, 7) to "54",
                at(6, 1) to "56",
                at(6, 7) to "59",
                at(2, 1) to "58",
            )
        assertFalse(none.fish(3))
        assertEquals(setOf(5, 8), none.at(2, 1))
    }

    @Test
    fun `xy-wing`() {
        val s = nineState(at(0, 0) to "12", at(0, 4) to "13", at(4, 0) to "23", at(4, 4) to "37")
        assertTrue(s.xyWing())
        assertEquals(setOf(7), s.at(4, 4))
        assertEquals(setOf(1, 3), s.at(0, 4))
        val none = nineState(at(0, 0) to "12", at(0, 4) to "13", at(4, 0) to "24", at(4, 4) to "37")
        assertFalse(none.xyWing())
        assertEquals(setOf(3, 7), none.at(4, 4))
    }

    @Test
    fun `x-wing with columns as base lines`() {
        // the 4s of columns 1 and 5 sit in rows 2 and 6 only
        val s =
            nineState(
                at(2, 1) to "41",
                at(6, 1) to "42",
                at(2, 5) to "43",
                at(6, 5) to "45",
                at(2, 3) to "47",
                at(6, 8) to "49",
            )
        assertTrue(s.fish(2))
        assertEquals(setOf(7), s.at(2, 3))
        assertEquals(setOf(9), s.at(6, 8))
        assertEquals(setOf(1, 4), s.at(2, 1))
    }

    @Test
    fun `swordfish with columns as base lines`() {
        val s =
            nineState(
                at(1, 0) to "51",
                at(4, 0) to "52",
                at(4, 3) to "53",
                at(7, 3) to "54",
                at(1, 6) to "56",
                at(7, 6) to "59",
                at(1, 2) to "58",
            )
        assertTrue(s.fish(3))
        assertEquals(setOf(8), s.at(1, 2))
        assertEquals(setOf(1, 5), s.at(1, 0))
    }

    @Test
    fun `xy-wing in 6x6 with a wing that shares only the box with the pivot`() {
        // pivot (0,0); wing (1,2) is in its box only, wing (3,0) in its column
        val s =
            state(
                SudokuSize.SIX,
                at(0, 0) to "12",
                at(1, 2) to "13",
                at(3, 0) to "23",
                at(3, 2) to "34",
                at(1, 0) to "36",
                at(1, 4) to "35",
            )
        assertTrue(s.xyWing())
        assertEquals(setOf(4), s.at(3, 2))
        assertEquals(setOf(6), s.at(1, 0))
        // (1,4) sees only one wing and keeps its 3
        assertEquals(setOf(3, 5), s.at(1, 4))
    }
}
