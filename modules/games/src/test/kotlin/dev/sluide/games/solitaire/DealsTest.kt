package dev.sluide.games.solitaire

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DealsTest {
    /** Fixed forever: a change here means every deal number shows another deal than before. */
    @ParameterizedTest
    @CsvSource(
        "KLONDIKE_DRAW_1, 1, 2D 7S 9S JC 8D JD 6H 9D 6S QD",
        "KLONDIKE_DRAW_1, 1000000, 2D AH 2C 3H JD 9S 3D 2H QD 10C",
        "KLONDIKE_DRAW_3, 1, KC 8H 2C 5S 7C JH 10H 8D 4C 7D",
        "KLONDIKE_DRAW_3, 1000000, QS AH 10D 9D 7D AS 6H QH 10H 2D",
        "FREECELL, 1, 6D AH JS 9C QH 10H 6H 2C AS 8S",
        "FREECELL, 1000000, 10S 4C 2D 7S 2H KH 8S 6D KD 3D",
        "SPIDER_1, 1, 8S 3S 6S 8S 9S JS 3S 2S JS QS",
        "SPIDER_1, 1000000, KS 8S 7S 10S 3S 4S 7S 2S 4S KS",
        "SPIDER_2, 1, QH 2H 9H KH AH 8S 6H QS 7S KS",
        "SPIDER_2, 1000000, 2S JH 3H QH AS 3H 7H JS 2S 4H",
        "SPIDER_4, 1, 2H 3H 6C QD 2S 2D 9D KC 8C 8H",
        "SPIDER_4, 1000000, JC JD KH 2S 5C 5S 4C 7C 4S JH",
    )
    fun `the first cards of a deal never change`(
        mode: Mode,
        number: Int,
        firstCards: String,
    ) {
        assertEquals(firstCards, codes(Deals.deck(mode, number).take(10)))
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `a deal is the same every time and differs from the next number`(mode: Mode) {
        assertEquals(Deals.deck(mode, 17), Deals.deck(mode, 17))
        assertNotEquals(Deals.deck(mode, 17), Deals.deck(mode, 18))
        assertNotEquals(Deals.orderedDeck(mode), Deals.deck(mode, 17))
    }

    @ParameterizedTest
    @CsvSource(
        "KLONDIKE_DRAW_1, 1, 1, 1, 1",
        "KLONDIKE_DRAW_3, 1, 1, 1, 1",
        "FREECELL, 1, 1, 1, 1",
        "SPIDER_1, 0, 0, 0, 8",
        "SPIDER_2, 0, 0, 4, 4",
        "SPIDER_4, 2, 2, 2, 2",
    )
    fun `a deck has the cards of its mode`(
        mode: Mode,
        clubs: Int,
        diamonds: Int,
        hearts: Int,
        spades: Int,
    ) {
        val copies = mapOf(Suit.CLUBS to clubs, Suit.DIAMONDS to diamonds, Suit.HEARTS to hearts, Suit.SPADES to spades)
        val expected =
            Suit.entries
                .flatMap { suit -> (1..13).map { Card(it, suit) to copies.getValue(suit) } }
                .filter { it.second > 0 }
                .toMap()
        listOf(1, 500_000, Deals.MAX_NUMBER).forEach { number ->
            val deck = Deals.deck(mode, number)
            assertEquals(13 * copies.values.sum(), deck.size)
            assertEquals(expected, deck.groupingBy { it }.eachCount())
        }
    }

    @Test
    fun `the ordered deck goes by suit, then rank`() {
        assertEquals("AC 2C 3C", codes(Deals.orderedDeck(Mode.FREECELL).take(3)))
        assertEquals("KS", Deals.orderedDeck(Mode.FREECELL).last().code)
        assertEquals("AH AH AH AH 2H", codes(Deals.orderedDeck(Mode.SPIDER_2).take(5)))
    }

    @Test
    fun `the modes get different deals for the same number`() {
        (1..20).forEach { number ->
            val decks = listOf(Mode.KLONDIKE_DRAW_1, Mode.KLONDIKE_DRAW_3, Mode.FREECELL).map { Deals.deck(it, number) }
            assertEquals(3, decks.toSet().size, "deal $number")
        }
    }

    @Test
    fun `many numbers give many different deals`() {
        assertEquals(2_000, (1..2_000).map { Deals.deck(Mode.KLONDIKE_DRAW_1, it) }.toSet().size)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 999_999, 1_000_000])
    fun `every number in range is accepted`(number: Int) {
        Mode.entries.forEach { assertEquals(Deals.orderedDeck(it).size, Deals.deck(it, number).size) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 1_000_001, Int.MIN_VALUE, Int.MAX_VALUE])
    fun `a number out of range is refused`(number: Int) {
        assertThrows<IllegalArgumentException> { Deals.deck(Mode.KLONDIKE_DRAW_1, number) }
    }
}
