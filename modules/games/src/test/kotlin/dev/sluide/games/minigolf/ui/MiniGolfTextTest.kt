package dev.sluide.games.minigolf.ui

import dev.sluide.games.minigolf.ScoreText
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MiniGolfTextTest {
    @ParameterizedTest
    @CsvSource("0,E", "2,+2", "-1,−1", "10,+10")
    fun `a score relative to par`(
        n: Int,
        text: String,
    ) {
        assertEquals(text, ScoreText.toPar(n))
    }

    @Test
    fun `the HUD omits the total on the first hole`() {
        assertEquals("Hole 1 · Par 2 · Stroke 0", HudPainter.text(1, 2, 0, null))
        assertEquals("Hole 7 · Par 3 · Stroke 2 · Total +1", HudPainter.text(7, 3, 2, 1))
        assertEquals("Hole 7 · Par 3 · Stroke 2 · Total E", HudPainter.text(7, 3, 2, 0))
    }

    @Test
    fun `the cells say strokes or a dash and colour by result`() {
        val cells = ScorecardCells.cells(listOf(2, 3, 3, 4), listOf(1, 3, 5, null), listOf(1, null, 4, null))

        assertEquals(listOf("1", "2", "3", "4"), cells.map { it.hole })
        assertEquals(listOf("2", "3", "3", "4"), cells.map { it.par })
        assertEquals(listOf("1", "3", "5", "–"), cells.map { it.you })
        assertEquals(listOf("1", "–", "4", "–"), cells.map { it.best })
        assertEquals(listOf("−1", "E", "+2", "–"), cells.map { it.toPar })
        assertEquals(
            listOf(HoleResult.UNDER_PAR, HoleResult.AT_PAR, HoleResult.OVER_PAR, HoleResult.NOT_PLAYED),
            cells.map { it.result },
        )
    }

    @Test
    fun `each result has its own colour`() {
        val colors = HoleResult.entries.map { MiniGolfPalette.resultColor(it) }

        assertEquals(colors.size, colors.toSet().size)
    }

    @Test
    fun `eighteen columns split into two rows of nine`() {
        assertEquals(listOf(0..8, 9..17), ScorecardCells.rows(18))
        assertEquals(listOf(0..4), ScorecardCells.rows(5))
        assertEquals(listOf(0..8, 9..10), ScorecardCells.rows(11))
    }

    @Test
    fun `the totals count only played holes`() {
        val pars = listOf(2, 3, 3)
        val scores = listOf(3, 3, null)

        assertEquals(Totals(strokes = 6, parSoFar = 5, toPar = 1), ScorecardCells.totals(pars, scores))
        assertEquals("Total 6 (+1)", ScorecardCells.totalLine(ScorecardCells.totals(pars, scores)))
        assertEquals("Total 0 (E)", ScorecardCells.totalLine(ScorecardCells.totals(pars, listOf(null, null, null))))
    }

    @Test
    fun `the final line adds new best only when beaten`() {
        val pars = listOf(2, 3)
        val scores = listOf(2, 2)

        val totals = ScorecardCells.totals(pars, scores)

        assertEquals("Round over · Total 4 (−1)", ScorecardCells.finalLine(totals, false))
        assertEquals("Round over · Total 4 (−1) — new best!", ScorecardCells.finalLine(totals, true))
    }

    @Test
    fun `the power colour runs from green over yellow to red`() {
        val low = AimPainter.powerColor(0.0)
        val mid = AimPainter.powerColor(0.5)
        val high = AimPainter.powerColor(1.0)

        assertTrue(low.green > low.red && low.green > low.blue, "green at 0")
        assertTrue(mid.red > 0.9 && mid.green > 0.8 && mid.blue < 0.4, "yellow at 0.5")
        assertTrue(high.red > high.green && high.red > high.blue, "red at 1")
        assertEquals(low, AimPainter.powerColor(-3.0))
        assertEquals(high, AimPainter.powerColor(7.0))
        assertNotEquals(AimPainter.powerColor(0.25), AimPainter.powerColor(0.75))
    }

    @Test
    fun `the aim line grows with the power`() {
        assertEquals(12.0, AimPainter.lengthFor(0.0))
        assertEquals(60.0, AimPainter.lengthFor(1.0))
    }
}
