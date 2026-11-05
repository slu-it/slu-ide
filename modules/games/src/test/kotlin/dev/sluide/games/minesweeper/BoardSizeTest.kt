package dev.sluide.games.minesweeper

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardSizeTest {
    @Test
    fun `presets have the classic sizes`() {
        assertEquals(BoardSize(9, 9, 10), Preset.BEGINNER.size)
        assertEquals(BoardSize(16, 16, 40), Preset.INTERMEDIATE.size)
        assertEquals(BoardSize(30, 16, 99), Preset.EXPERT.size)
        assertEquals(listOf("Beginner", "Intermediate", "Expert"), Preset.entries.map { it.label })
    }

    @Test
    fun `a preset choice has the size of its preset`() {
        assertEquals(Preset.EXPERT.size, SizeChoice.OfPreset(Preset.EXPERT).size)
        assertEquals(BoardSize(7, 8, 9), SizeChoice.Custom(BoardSize(7, 8, 9)).size)
    }

    @Test
    fun `maxMines keeps the first opening free`() {
        assertEquals(72, BoardSize.maxMines(9, 9))
        assertEquals(16, BoardSize.maxMines(5, 5))
    }

    @ParameterizedTest
    @CsvSource("4,5,1", "51,5,1", "5,4,1", "5,31,1", "5,5,0", "5,5,17", "9,9,73")
    fun `values outside the limits are refused`(
        width: Int,
        height: Int,
        mines: Int,
    ) {
        assertThrows<IllegalArgumentException> { BoardSize(width, height, mines) }
    }

    @Test
    fun `the limits themselves are accepted`() {
        BoardSize(5, 5, 1)
        BoardSize(5, 5, 16)
        BoardSize(50, 30, 1491)
    }

    @ParameterizedTest
    @CsvSource(
        "1,1,0,5,5,1",
        "100,100,9999,50,30,1491",
        "5,5,99,5,5,16",
        "20,12,40,20,12,40",
        "9,9,-3,9,9,1",
    )
    fun `clamped moves each value into its range`(
        width: Int,
        height: Int,
        mines: Int,
        w: Int,
        h: Int,
        m: Int,
    ) {
        assertEquals(BoardSize(w, h, m), BoardSize.clamped(width, height, mines))
    }
}
