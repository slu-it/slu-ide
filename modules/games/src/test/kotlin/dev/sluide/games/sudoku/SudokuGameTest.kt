package dev.sluide.games.sudoku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SudokuGameTest {
    private val four = SudokuSize.FOUR

    private val solution4 = Grid.of(four, listOf("1234", "3412", "2143", "4321"))

    // Empty cells: (0,2)=3 (0,3)=4 (1,1)=4 (1,2)=1 (2,0)=2 (2,2)=4 (3,1)=3 (3,3)=1
    private val givens4 = Grid.of(four, listOf("12..", "3..2", ".1.3", "4.2."))

    private fun game4(givens: Grid = givens4) = SudokuGame(Puzzle(four, Level.EASY, givens, solution4, Tier.T1))

    private fun c(
        row: Int,
        col: Int,
    ) = Cell(row, col)

    private fun SudokuGame.solveAll() {
        listOf(
            c(0, 2) to 3,
            c(0, 3) to 4,
            c(1, 1) to 4,
            c(1, 2) to 1,
            c(2, 0) to 2,
            c(2, 2) to 4,
            c(3, 1) to 3,
            c(3, 3) to 1,
        ).forEach { (cell, digit) -> place(cell, digit) }
    }

    private fun SudokuGame.state(): List<Any> = four.cells.map { digit(it) to notes(it) }

    @Test
    fun `an entry that clashes with a given reports both cells`() {
        val game = game4()

        game.place(c(0, 2), 1)

        assertEquals(setOf(c(0, 0), c(0, 2)), game.conflicts)
    }

    @Test
    fun `two clashing entries and the given they clash with are conflicts`() {
        val game = game4()

        game.place(c(0, 2), 3)
        game.place(c(1, 2), 3)

        assertEquals(setOf(c(0, 2), c(1, 2), c(1, 0)), game.conflicts)
    }

    @Test
    fun `a wrong entry without a clash is no conflict`() {
        val game = game4()

        game.place(c(0, 2), 4)

        assertTrue(game.conflicts.isEmpty())
    }

    @Test
    fun `conflicts across boxes of a 9x9 grid`() {
        val nine = SudokuSize.NINE
        val solution =
            Grid.of(
                nine,
                listOf(
                    "534678912",
                    "672195348",
                    "198342567",
                    "859761423",
                    "426853791",
                    "713924856",
                    "961537284",
                    "287419635",
                    "345286179",
                ),
            )
        val givens = Grid.of(nine, listOf("5........") + List(8) { "........." })
        val game = SudokuGame(Puzzle(nine, Level.MEDIUM, givens, solution, Tier.T1))

        game.place(c(0, 8), 5)
        game.place(c(8, 0), 5)

        assertEquals(setOf(c(0, 0), c(0, 8), c(8, 0)), game.conflicts)
    }

    @Test
    fun `placing clears the notes of the cell and of its peers in one undo step`() {
        val game = game4()
        game.toggleNote(c(0, 2), 3)
        game.toggleNote(c(0, 3), 3)
        game.toggleNote(c(0, 3), 4)
        game.toggleNote(c(1, 2), 3)
        game.toggleNote(c(3, 3), 3)
        val before = game.state()

        game.place(c(0, 2), 3)

        assertEquals(3, game.digit(c(0, 2)))
        assertEquals(emptySet(), game.notes(c(0, 2)))
        assertEquals(setOf(4), game.notes(c(0, 3)))
        assertEquals(emptySet(), game.notes(c(1, 2)))
        assertEquals(setOf(3), game.notes(c(3, 3)))
        assertTrue(game.undo())
        assertEquals(before, game.state())
    }

    @Test
    fun `placing replaces a different entry and ignores invalid input`() {
        val game = game4()
        game.place(c(0, 2), 4)

        assertNull(game.place(c(0, 2), 4))
        assertNull(game.place(c(0, 0), 2))
        assertNull(game.place(c(0, 3), 0))
        assertNull(game.place(c(0, 3), 5))
        assertNotNull(game.place(c(0, 2), 3))

        assertEquals(3, game.digit(c(0, 2)))
    }

    @Test
    fun `erase removes the entry first and then the notes`() {
        val game = game4()
        game.toggleNote(c(0, 2), 3)
        game.place(c(0, 3), 4)

        assertNotNull(game.erase(c(0, 3)))
        assertEquals(0, game.digit(c(0, 3)))
        assertNull(game.erase(c(0, 3)))
        assertNotNull(game.erase(c(0, 2)))
        assertEquals(emptySet(), game.notes(c(0, 2)))
        assertNull(game.erase(c(0, 2)))
        assertNull(game.erase(c(0, 0)))
    }

    @Test
    fun `notes toggle on empty cells only`() {
        val game = game4()
        game.place(c(0, 2), 3)

        assertNull(game.toggleNote(c(0, 2), 4))
        assertNull(game.toggleNote(c(0, 0), 3))
        assertNull(game.toggleNote(c(0, 3), 5))
        assertNotNull(game.toggleNote(c(0, 3), 3))
        assertEquals(setOf(3), game.notes(c(0, 3)))
        assertNotNull(game.toggleNote(c(0, 3), 3))
        assertEquals(emptySet(), game.notes(c(0, 3)))
    }

    @Test
    fun `fill notes replaces the notes with the candidates and does nothing twice`() {
        val game = game4()
        game.toggleNote(c(0, 2), 1)

        assertNotNull(game.fillNotes())

        assertEquals(setOf(3, 4), game.notes(c(0, 2)))
        assertEquals(emptySet(), game.notes(c(0, 0)))
        assertEquals(givens4.candidates(c(3, 3)), game.notes(c(3, 3)))
        assertNull(game.fillNotes())
        assertTrue(game.undo())
        assertEquals(setOf(1), game.notes(c(0, 2)))
    }

    @Test
    fun `undo and redo restore every kind of step and a new step clears redo`() {
        val steps: List<(SudokuGame) -> Unit> =
            listOf(
                { it.place(c(0, 2), 3) },
                { it.erase(c(0, 3)) },
                { it.toggleNote(c(1, 1), 2) },
                { it.fillNotes() },
                { it.hint(c(2, 0)) },
            )
        steps.forEach { step ->
            val game = game4()
            game.place(c(0, 3), 4)
            val before = game.state()
            step(game)
            val after = game.state()

            assertTrue(game.undo())
            assertEquals(before, game.state())
            assertTrue(game.canRedo)
            assertTrue(game.redo())
            assertEquals(after, game.state())
        }
        val game = game4()
        game.place(c(0, 2), 3)
        game.undo()
        game.toggleNote(c(0, 2), 3)
        assertFalse(game.canRedo)
        assertFalse(game.redo())
    }

    @Test
    fun `only newly completed conflict free units are reported`() {
        val game = game4()
        game.place(c(0, 2), 3)

        assertEquals(emptyList(), game.place(c(0, 3), 3)?.completedUnits)
        val step = game.place(c(0, 3), 4)

        assertEquals(listOf(four.units[0]), step?.completedUnits)
        assertFalse(step?.solved ?: true)
        assertEquals(emptyList(), game.place(c(3, 1), 3)?.completedUnits)
    }

    @Test
    fun `filling the last cell solves and ends undo`() {
        val game = game4()
        game.solveAll()

        assertTrue(game.isSolved)
        assertFalse(game.canUndo)
        assertFalse(game.undo())
        assertNull(game.place(c(0, 2), 1))
        assertNull(game.erase(c(0, 2)))
        assertNull(game.hint(null))
    }

    @Test
    fun `the last step reports solved`() {
        val game = game4()
        game.place(c(0, 2), 3)
        game.place(c(0, 3), 4)
        game.place(c(1, 1), 4)
        game.place(c(1, 2), 1)
        game.place(c(2, 0), 2)
        game.place(c(2, 2), 4)
        game.place(c(3, 1), 3)

        assertTrue(game.place(c(3, 3), 1)?.solved == true)
    }

    @Test
    fun `a full grid with a conflict is not solved`() {
        val stuck = game4()
        listOf(
            c(0, 2) to 3,
            c(0, 3) to 4,
            c(1, 1) to 4,
            c(1, 2) to 1,
            c(2, 0) to 2,
            c(2, 2) to 4,
            c(3, 1) to 3,
            c(3, 3) to 3,
        ).forEach { (cell, digit) -> stuck.place(cell, digit) }

        assertFalse(stuck.isSolved)
        assertTrue(stuck.canUndo)
    }

    @Test
    fun `a hint removes the selected wrong entry first`() {
        val game = game4()
        game.place(c(0, 2), 4)
        game.place(c(1, 1), 3)

        assertEquals(HintResult.Fixed(c(1, 1), 3), game.hint(c(1, 1)))

        assertEquals(0, game.digit(c(1, 1)))
        assertEquals(4, game.digit(c(0, 2)))
        assertEquals(1, game.hints)
    }

    @Test
    fun `a hint removes the first wrong entry when the selection is not wrong`() {
        val game = game4()
        game.place(c(1, 1), 3)
        game.place(c(0, 2), 4)

        assertEquals(HintResult.Fixed(c(0, 2), 4), game.hint(c(3, 3)))
        assertEquals(HintResult.Fixed(c(1, 1), 3), game.hint(null))
    }

    @Test
    fun `a hint fills an empty selected cell with the solution digit`() {
        val game = game4()

        val result = game.hint(c(2, 2)) as HintResult.Filled

        assertEquals(c(2, 2), result.cell)
        assertEquals(4, result.digit)
        assertEquals(4, game.digit(c(2, 2)))
    }

    @Test
    fun `a hint on a filled selection fills the cell of the next deduction`() {
        val game = game4()
        val expected = assertNotNull(Grader.nextDeduction(givens4))

        val result = game.hint(c(0, 0)) as HintResult.Filled

        assertEquals(expected.cell, result.cell)
        assertEquals(solution4[expected.cell], result.digit)
    }

    @Test
    fun `a hint falls back to the first empty cell when nothing can be deduced`() {
        val empty = Grid.empty(four)
        assertNull(Grader.nextDeduction(empty))
        val game = game4(empty)

        val result = game.hint(null) as HintResult.Filled

        assertEquals(c(0, 0), result.cell)
        assertEquals(1, result.digit)
    }

    @Test
    fun `undoing a hint keeps the hint count`() {
        val game = game4()
        game.hint(c(2, 2))

        game.undo()

        assertEquals(0, game.digit(c(2, 2)))
        assertEquals(1, game.hints)
    }

    @Test
    fun `restart clears entries notes and history but keeps the hints`() {
        val game = game4()
        game.place(c(0, 2), 3)
        game.toggleNote(c(0, 3), 4)
        game.hint(c(2, 2))

        game.restart()

        assertFalse(game.hasEntries)
        assertFalse(game.canUndo)
        assertFalse(game.canRedo)
        assertEquals(1, game.hints)
        assertEquals(0, game.digit(c(0, 2)))
    }

    @Test
    fun `remaining counts givens and entries and never goes below zero`() {
        val game = game4()

        assertEquals(2, game.remaining(1))
        game.place(c(0, 2), 1)
        game.place(c(1, 1), 1)
        game.place(c(1, 2), 1)
        assertEquals(0, game.remaining(1))
        assertEquals(1, game.remaining(2))
    }

    @Test
    fun `hasEntries is true for a note`() {
        val game = game4()
        assertFalse(game.hasEntries)

        game.toggleNote(c(0, 2), 3)

        assertTrue(game.hasEntries)
    }
}
