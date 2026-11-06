package dev.sluide.games.sudoku

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The techniques must never remove the solution digit of a cell nor place a wrong digit. */
class GraderSoundnessTest {
    /** The tier of each technique as the ADR lists it, by the names `gradeTraced` reports. */
    private val specTier =
        mapOf(
            "nakedSingle" to Tier.T1,
            "hiddenSingle" to Tier.T1,
            "pointing" to Tier.T2,
            "claiming" to Tier.T2,
            "nakedPair" to Tier.T2,
            "hiddenPair" to Tier.T2,
            "nakedTriple" to Tier.T3,
            "hiddenTriple" to Tier.T3,
            "xWing" to Tier.T3,
            "swordfish" to Tier.T4,
            "xyWing" to Tier.T4,
        )

    private fun seedsFor(
        size: SudokuSize,
        level: Level,
    ) = if (size == SudokuSize.NINE && level >= Level.HARD) 20 else 6

    /** Runs the traced grader; returns how often each technique made progress. */
    private fun checkSound(
        puzzle: Puzzle,
        seed: Int,
        counts: MutableMap<String, Int>,
    ) {
        val solution = puzzle.solution
        var highest = Tier.T1
        val tier =
            Grader.gradeTraced(puzzle.givens) { technique, eliminated, placed ->
                counts.merge(technique, 1, Int::plus)
                highest = maxOf(highest, checkNotNull(specTier[technique]) { "unknown technique $technique" })
                val where = "${puzzle.size} ${puzzle.level} seed $seed technique $technique"
                eliminated.forEach { (cell, digit) ->
                    assertEquals(false, solution[cell] == digit, "$where removed the solution digit $digit at $cell")
                }
                placed?.let { (cell, digit) ->
                    assertEquals(solution[cell], digit, "$where placed wrong digit at $cell")
                }
            }
        assertNotNull(tier, "${puzzle.size} ${puzzle.level} seed $seed is not solvable by the grader")
        assertEquals(puzzle.tier, tier)
        assertEquals(highest, puzzle.tier, "${puzzle.size} ${puzzle.level} seed $seed: tier of the techniques used")
    }

    @Test
    fun `no technique removes a solution digit or places a wrong one`() {
        val counts = mutableMapOf<String, Int>()
        for (size in SudokuSize.entries) {
            for (level in Level.entries) {
                for (seed in 0 until seedsFor(size, level)) {
                    // counting clock: up to 1000 attempts, independent of the machine's speed
                    val puzzle =
                        Generator(
                            Random(seed * 17 + level.ordinal),
                            1000,
                            countingClock(),
                        ).generate(size, level)
                    if (size == SudokuSize.NINE) {
                        assertEquals(Tier.entries[level.ordinal], puzzle.tier, "$level seed $seed misses its tier")
                    }
                    checkSound(puzzle, seed, counts)
                }
            }
        }
        // every technique must have been exercised, or the check proves little
        // (hiddenTriple did not fire on an earlier corpus; the current one covers all of them)
        specTier.keys.forEach { assertTrue((counts[it] ?: 0) > 0, "$it never fired: $counts") }
    }
}
