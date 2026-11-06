package dev.sluide.games.wordguess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordGuessGameTest {
    private val english = Language.EN.alphabet

    private fun game(
        answer: String = "CRANE",
        allowed: Set<String> = setOf("SLATE", "ABOUT", "CRANE", "MOUSE", "HOUSE", "PIZZA", "ARISE", "STEAM", "ALOOF"),
    ) = WordGuessGame(answer, { it in allowed }, english)

    private fun WordGuessGame.guess(word: String): SubmitResult {
        word.forEach { type(it) }
        return submit()
    }

    @Test
    fun `typing stops at five letters`() {
        val game = game()

        "slate".forEach { assertTrue(game.type(it)) }
        assertFalse(game.type('X'))

        assertEquals("SLATE", game.input)
    }

    @Test
    fun `letters outside the alphabet are ignored`() {
        val game = game()

        assertFalse(game.type('Ä'))
        assertFalse(game.type('1'))
        assertFalse(game.type('ß'))
        assertFalse(game.type(' '))
        assertEquals("", game.input)
    }

    @Test
    fun `german umlauts are typed in the german alphabet`() {
        val game = WordGuessGame("BÜHNE", { true }, Language.DE.alphabet)

        assertTrue(game.type('ü'))
        assertFalse(game.type('ß'))
        assertEquals("Ü", game.input)
    }

    @Test
    fun `an answer outside the alphabet is rejected`() {
        assertFailsWith<IllegalArgumentException> { WordGuessGame("BÜHNE", { true }, english) }
        assertFailsWith<IllegalArgumentException> { WordGuessGame("CAT", { true }, english) }
    }

    @Test
    fun `backspace removes the last letter`() {
        val game = game()

        assertFalse(game.backspace())
        game.type('a')
        game.type('b')
        assertTrue(game.backspace())

        assertEquals("A", game.input)
    }

    @Test
    fun `submitting too few letters is refused`() {
        val game = game()
        game.type('A')

        assertEquals(SubmitResult.NOT_ENOUGH_LETTERS, game.submit())
        assertEquals("A", game.input)
    }

    @Test
    fun `a word not in the list keeps the input and uses no row`() {
        val game = game()

        assertEquals(SubmitResult.NOT_IN_LIST, game.guess("ZZZZZ"))

        assertEquals("ZZZZZ", game.input)
        assertTrue(game.guesses.isEmpty())
        assertEquals(RoundState.RUNNING, game.state)
    }

    @Test
    fun `the answer is allowed even when the list lacks it`() {
        val game = game(answer = "CRANE", allowed = emptySet())

        assertEquals(SubmitResult.ACCEPTED, game.guess("CRANE"))
        assertEquals(RoundState.WON, game.state)
    }

    @Test
    fun `winning on the third guess ends the round`() {
        val game = game()

        assertEquals(SubmitResult.ACCEPTED, game.guess("SLATE"))
        assertEquals(SubmitResult.ACCEPTED, game.guess("ABOUT"))
        assertEquals(SubmitResult.ACCEPTED, game.guess("CRANE"))

        assertEquals(RoundState.WON, game.state)
        assertEquals(3, game.guesses.size)
        assertEquals("", game.input)
        assertFalse(game.type('A'))
        assertFalse(game.backspace())
        assertEquals(SubmitResult.IGNORED, game.submit())
    }

    @Test
    fun `six wrong guesses lose the round`() {
        val game = game()

        repeat(5) {
            game.guess("SLATE")
            assertEquals(RoundState.RUNNING, game.state)
        }
        game.guess("ABOUT")

        assertEquals(RoundState.LOST, game.state)
        assertEquals(MAX_GUESSES, game.guesses.size)
        assertEquals(SubmitResult.IGNORED, game.guess("CRANE"))
    }

    @Test
    fun `a correct letter stays correct on the keyboard when it is only present later`() {
        val game = game(answer = "CRANE")

        game.guess("ARISE") // E CORRECT
        game.guess("STEAM") // E only PRESENT here
        assertEquals(LetterState.CORRECT, game.letterStates['E'])
        assertEquals(LetterState.ABSENT, game.letterStates['S'])
        assertEquals(LetterState.PRESENT, game.letterStates['A'])
    }

    @Test
    fun `a present letter becomes correct and stays so beside an absent copy`() {
        val game = game(answer = "ABOUT")

        game.guess("MOUSE") // O present
        assertEquals(LetterState.PRESENT, game.letterStates['O'])
        game.guess("ALOOF") // O at 3 CORRECT, the second O ABSENT
        assertEquals(LetterState.CORRECT, game.letterStates['O'])
        assertEquals(LetterState.CORRECT, game.letterStates['A'])
    }
}
