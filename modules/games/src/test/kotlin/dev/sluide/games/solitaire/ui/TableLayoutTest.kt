package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Variant
import dev.sluide.games.solitaire.cards
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableLayoutTest {
    private val klondike = emptyTable(Mode.KLONDIKE_DRAW_1)

    @ParameterizedTest
    @CsvSource("KLONDIKE_DRAW_1,13", "FREECELL,16", "SPIDER_1,19")
    fun `every pile of each variant has a slot`(
        mode: Mode,
        count: Int,
    ) {
        val table = emptyTable(mode)
        assertEquals(table.piles.keys, layout(table, 1000.0, 700.0).slots.keys)
        assertEquals(count, table.piles.size)
    }

    @ParameterizedTest
    @CsvSource("KLONDIKE_DRAW_1", "FREECELL", "SPIDER_1")
    fun `the slots fit the width`(mode: Mode) {
        val result = layout(emptyTable(mode), 1000.0, 700.0)
        assertTrue(result.slots.values.all { it.x >= 0 && it.x + it.w <= 1000.0 })
        assertEquals(1000.0, result.prefWidth)
    }

    @Test
    fun `a narrow table keeps the minimum card width and asks for more width`() {
        val result = layout(klondike, 300.0, 700.0)
        assertEquals(48.0, result.cardWidth)
        assertTrue(result.prefWidth > 300.0)
    }

    @Test
    fun `a wide table caps the card width and centres the cards`() {
        val result = layout(klondike, 3000.0, 700.0)
        assertEquals(160.0, result.cardWidth)
        assertEquals(3000.0, result.prefWidth)
        val first = result.slots.getValue(tableau(0)).x
        val last = result.slots.getValue(tableau(6)).let { it.x + it.w }
        assertEquals(first, 3000.0 - last, 1e-6)
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["KLONDIKE_DRAW_1", "FREECELL", "SPIDER_1"])
    fun `the top row piles sit over the columns of their variant`(mode: Mode) {
        val foundationCount = if (mode.variant == Variant.SPIDER) 8 else 4

        fun foundations(first: Int) =
            (0 until foundationCount).associate {
                PileId(PileKind.FOUNDATION, it) to
                    first + it
            }

        val expected: Map<PileId, Int> =
            when (mode.variant) {
                Variant.KLONDIKE -> mapOf(PileId(PileKind.STOCK) to 0, PileId(PileKind.WASTE) to 1) + foundations(3)
                Variant.FREECELL -> (0..3).associate { PileId(PileKind.CELL, it) to it } + foundations(4)
                Variant.SPIDER -> mapOf(PileId(PileKind.STOCK) to 0) + foundations(2)
            }
        val result = layout(emptyTable(mode), 1000.0, 700.0)
        for ((id, column) in expected) {
            assertEquals(result.slots.getValue(tableau(column)).x, result.slots.getValue(id).x, "$id")
        }
    }

    @Test
    fun `the top of the waste fans out to the right and a foundation shows only its top card`() {
        val waste = PileId(PileKind.WASTE)
        val foundation = PileId(PileKind.FOUNDATION, 0)
        val table =
            emptyTable(
                Mode.KLONDIKE_DRAW_3,
                mapOf(
                    waste to
                        dev.sluide.games.solitaire.Pile
                            .allFaceUp(cards("AH 2H 3H 4H")),
                    foundation to pileOf(13),
                ),
                fan = 3,
            )
        val result = layout(table, 1000.0, 700.0)
        val shown = result.cards.filter { it.pile == waste }
        assertEquals(listOf(0, 1, 2, 3), shown.map { it.index })
        val base = result.slots.getValue(waste).x
        val step = 0.22 * result.cardWidth
        assertEquals(listOf(0.0, 0.0, step, 2 * step), shown.map { it.rect.x - base })
        assertEquals(listOf(12), result.cards.filter { it.pile == foundation }.map { it.index })
    }

    @Test
    fun `overlaps are 12 and 28 percent of the card height when there is room`() {
        val table = emptyTable(Mode.KLONDIKE_DRAW_1, mapOf(tableau(0) to pileOf(5, up = 3)))
        val result = layout(table, 1000.0, 900.0)
        val ys = result.cards.filter { it.pile == tableau(0) }.map { it.rect.y }
        val h = result.cardHeight
        val steps = ys.zipWithNext { a, b -> b - a }
        listOf(0.12, 0.12, 0.28, 0.28).forEachIndexed { i, f -> assertEquals(f * h, steps[i], 1e-9) }
    }

    @Test
    fun `a tall column is squeezed to fit the height`() {
        val table = emptyTable(Mode.KLONDIKE_DRAW_1, mapOf(tableau(0) to pileOf(14, up = 8)))
        val result = layout(table, 1000.0, 700.0)
        val column = result.cards.filter { it.pile == tableau(0) }
        val h = result.cardHeight
        val faceUpStep = column[9].rect.y - column[8].rect.y
        assertTrue(faceUpStep < 0.28 * h && faceUpStep >= 0.14 * h - 1e-9)
        assertTrue(column.last().rect.y + h <= 700.0)
        assertEquals(700.0, result.prefHeight)
    }

    @Test
    fun `beyond the minimum overlaps the preferred height grows`() {
        val table = emptyTable(Mode.KLONDIKE_DRAW_1, mapOf(tableau(0) to pileOf(19, up = 13)))
        val result = layout(table, 1000.0, 300.0)
        val column = result.cards.filter { it.pile == tableau(0) }
        val h = result.cardHeight
        assertEquals(0.14 * h, column[14].rect.y - column[13].rect.y, 1e-9)
        assertEquals(0.06 * h, column[1].rect.y - column[0].rect.y, 1e-9)
        assertTrue(result.prefHeight > 300.0)
        assertEquals(column.last().rect.y + h + 8.0, result.prefHeight, 1e-9)
    }
}
