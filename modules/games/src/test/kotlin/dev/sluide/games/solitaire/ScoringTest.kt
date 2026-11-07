package dev.sluide.games.solitaire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ScoringTest {
    @ParameterizedTest
    @CsvSource(
        "KLONDIKE_DRAW_1,0,0",
        "KLONDIKE_DRAW_3,9999,0",
        "KLONDIKE_DRAW_1,10000,-2",
        "KLONDIKE_DRAW_3,600000,-120",
        "FREECELL,600000,0",
        "SPIDER_4,600000,0",
    )
    fun `time points are minus 2 per full 10 seconds in Klondike only`(
        mode: Mode,
        millis: Long,
        expected: Int,
    ) {
        assertEquals(expected, Scoring.timePoints(mode, millis))
    }

    @ParameterizedTest
    @CsvSource(
        "KLONDIKE_DRAW_1,29000,0",
        "KLONDIKE_DRAW_1,30000,23333",
        "KLONDIKE_DRAW_3,31000,22580",
        "KLONDIKE_DRAW_1,600000,1166",
        "FREECELL,29999,0",
        "FREECELL,30000,23333",
        "FREECELL,600000,1166",
        "SPIDER_1,30000,0",
        "SPIDER_4,600000,0",
    )
    fun `time bonus is 700000 per second from 30 seconds, none in Spider`(
        mode: Mode,
        millis: Long,
        expected: Int,
    ) {
        assertEquals(expected, Scoring.timeBonus(mode, millis))
    }

    @ParameterizedTest
    @CsvSource("KLONDIKE_DRAW_1,-5,0", "KLONDIKE_DRAW_3,7,7", "FREECELL,-5,-5", "SPIDER_2,-5,-5")
    fun `only Klondike is floored at 0`(
        mode: Mode,
        raw: Int,
        expected: Int,
    ) {
        assertEquals(expected, Scoring.floor(mode, raw))
    }

    @ParameterizedTest
    @CsvSource("SPIDER_1,500", "SPIDER_4,500", "KLONDIKE_DRAW_1,0", "FREECELL,0")
    fun `Spider starts at 500`(
        mode: Mode,
        expected: Int,
    ) {
        assertEquals(expected, Scoring.base(mode))
    }
}
