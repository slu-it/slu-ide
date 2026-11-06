package dev.sluide.games.wordguess

import dev.sluide.games.wordguess.LetterState.ABSENT
import dev.sluide.games.wordguess.LetterState.CORRECT
import dev.sluide.games.wordguess.LetterState.PRESENT
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {
    @Test
    fun `duplicate letters in the guess are marked only as often as the answer has them`() {
        assertEquals(listOf(PRESENT, PRESENT, CORRECT, CORRECT, ABSENT), score("BABES", "ABBEY"))
        assertEquals(listOf(ABSENT, PRESENT, CORRECT, PRESENT, PRESENT), score("KEBAB", "ABBEY"))
        assertEquals(listOf(CORRECT, CORRECT, PRESENT, ABSENT, ABSENT), score("ABYSS", "ABBEY"))
    }

    @Test
    fun `repeated guess letters against repeated answer letters`() {
        assertEquals(listOf(PRESENT, PRESENT, ABSENT, ABSENT, ABSENT), score("EERIE", "SPEED"))
        assertEquals(listOf(PRESENT, ABSENT, ABSENT, PRESENT, PRESENT), score("ERASE", "SPEED"))
    }

    @Test
    fun `the exact match is taken before earlier copies of its letter`() {
        // A scorer in one pass would mark the first E PRESENT.
        assertEquals(listOf(ABSENT, ABSENT, PRESENT, ABSENT, CORRECT), score("EERIE", "CRANE"))
    }

    @Test
    fun `all correct and all absent`() {
        assertEquals(List(5) { CORRECT }, score("CRANE", "CRANE"))
        assertEquals(List(5) { ABSENT }, score("FUZZY", "CRANE"))
    }

    @Test
    fun `an umlaut is a letter of its own`() {
        assertEquals(listOf(CORRECT, ABSENT, CORRECT, CORRECT, CORRECT), score("BUHNE", "BÜHNE"))
    }
}
