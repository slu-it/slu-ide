package dev.sluide.games.wordguess

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardLayoutTest {
    @Test
    fun `each layout has every letter of its alphabet once`() {
        for (language in Language.entries) {
            val letters = keyboardRows(language).flatten().filterIsInstance<KeyboardKey.Letter>().map { it.letter }

            assertEquals(language.alphabet.toList().sorted(), letters.sorted(), language.name)
        }
    }

    @Test
    fun `the last row is framed by Enter and Backspace`() {
        for (language in Language.entries) {
            val last = keyboardRows(language).last()

            assertEquals(KeyboardKey.Enter, last.first())
            assertEquals(KeyboardKey.Backspace, last.last())
        }
    }

    @Test
    fun `the rows follow the national layouts`() {
        fun row(
            language: Language,
            index: Int,
        ) = keyboardRows(language)[index].filterIsInstance<KeyboardKey.Letter>().joinToString("") { "${it.letter}" }

        assertEquals("QWERTYUIOP", row(Language.EN, 0))
        assertEquals("QWERTZUIOPÜ", row(Language.DE, 0))
        assertEquals("ASDFGHJKLÖÄ", row(Language.DE, 1))
        assertEquals("YXCVBNM", row(Language.DE, 2))
    }
}
