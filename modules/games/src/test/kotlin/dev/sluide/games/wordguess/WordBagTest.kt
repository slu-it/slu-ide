package dev.sluide.games.wordguess

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class WordBagTest {
    private val words = listOf("ALPHA", "BRAVO", "CHART", "DELTA", "ECHOS")

    @Test
    fun `every word is drawn once before any repeats`() {
        val bag = WordBag(words, Random(1))

        assertEquals(words.toSet(), List(words.size) { bag.next() }.toSet())
    }

    @Test
    fun `the bag refills and the first word differs from the last one`() {
        repeat(50) { seed ->
            val bag = WordBag(listOf("AAAAA", "BBBBB"), Random(seed))
            val draws = List(40) { bag.next() }

            draws.chunked(2).forEach { assertEquals(setOf("AAAAA", "BBBBB"), it.toSet()) }
            draws.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        }
    }

    @Test
    fun `duplicate answers do not repeat within a round`() {
        val bag = WordBag(listOf("ALPHA", "ALPHA", "BRAVO"), Random(3))

        assertEquals(setOf("ALPHA", "BRAVO"), List(2) { bag.next() }.toSet())
    }

    @Test
    fun `a single answer repeats`() {
        val bag = WordBag(listOf("ALPHA"))

        assertEquals(listOf("ALPHA", "ALPHA"), listOf(bag.next(), bag.next()))
    }

    @Test
    fun `a seeded random is deterministic`() {
        val first = WordBag(words, Random(42))
        val second = WordBag(words, Random(42))

        assertEquals(List(12) { first.next() }, List(12) { second.next() })
    }

    @Test
    fun `no answers are rejected`() {
        assertFailsWith<IllegalArgumentException> { WordBag(emptyList()) }
    }
}
