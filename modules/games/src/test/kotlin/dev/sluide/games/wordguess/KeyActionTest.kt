package dev.sluide.games.wordguess

import javafx.scene.input.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyActionTest {
    @Test
    fun `keys map to actions`() {
        assertEquals(KeyAction.SUBMIT, keyAction(KeyCode.ENTER))
        assertEquals(KeyAction.BACKSPACE, keyAction(KeyCode.BACK_SPACE))
        assertEquals(KeyAction.NEXT_WORD, keyAction(KeyCode.F2))
        assertNull(keyAction(KeyCode.A))
        assertNull(keyAction(KeyCode.DELETE))
    }

    @Test
    fun `modifiers disable the actions`() {
        assertNull(keyAction(KeyCode.ENTER, hasModifier = true))
        assertNull(keyAction(KeyCode.F2, hasModifier = true))
    }

    @Test
    fun `typed characters become upper case letters of the language`() {
        assertEquals('A', typedLetter("a", Language.EN))
        assertEquals('Z', typedLetter("Z", Language.EN))
        assertEquals('Ä', typedLetter("ä", Language.DE))
        assertEquals('Ü', typedLetter("Ü", Language.DE))
    }

    @Test
    fun `other characters type nothing`() {
        assertNull(typedLetter("ä", Language.EN))
        assertNull(typedLetter("ß", Language.DE))
        assertNull(typedLetter("1", Language.EN))
        assertNull(typedLetter("\r", Language.EN))
        assertNull(typedLetter("", Language.EN))
        assertNull(typedLetter("ab", Language.EN))
    }

    @Test
    fun `Alt is let through, Shortcut, Ctrl and Meta are not`() {
        assertEquals('A', typedLetter("a", Language.EN, alt = true))
        assertEquals('Ä', typedLetter("ä", Language.DE, alt = true))
        assertNull(typedLetter("a", Language.EN, control = true, alt = true))
        assertNull(typedLetter("w", Language.EN, shortcut = true))
        assertNull(typedLetter("a", Language.EN, meta = true))
        assertNull(typedLetter("a", Language.EN, control = true))
    }
}
