package dev.sluide.games.sudoku

import javafx.scene.input.KeyCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyActionTest {
    private fun key(
        code: KeyCode,
        shift: Boolean = false,
        shortcut: Boolean = false,
        other: Boolean = false,
    ) = keyAction(code, shift, shortcut, other)

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9])
    fun `digit keys and the numpad place digits and with shift note them`(digit: Int) {
        listOf("DIGIT", "NUMPAD").map { KeyCode.valueOf("$it$digit") }.forEach { code ->
            assertEquals(KeyAction.Digit(digit), key(code))
            assertEquals(KeyAction.Note(digit), key(code, shift = true))
        }
    }

    @Test
    fun `erase keys`() {
        listOf(KeyCode.DIGIT0, KeyCode.NUMPAD0, KeyCode.BACK_SPACE, KeyCode.DELETE).forEach {
            assertEquals(KeyAction.Erase, key(it))
        }
    }

    @Test
    fun `arrows and WASD move`() {
        assertEquals(KeyAction.Move(-1, 0), key(KeyCode.UP))
        assertEquals(KeyAction.Move(-1, 0), key(KeyCode.W))
        assertEquals(KeyAction.Move(1, 0), key(KeyCode.DOWN))
        assertEquals(KeyAction.Move(1, 0), key(KeyCode.S))
        assertEquals(KeyAction.Move(0, -1), key(KeyCode.LEFT))
        assertEquals(KeyAction.Move(0, -1), key(KeyCode.A))
        assertEquals(KeyAction.Move(0, 1), key(KeyCode.RIGHT))
        assertEquals(KeyAction.Move(0, 1), key(KeyCode.D))
    }

    @Test
    fun `letter and function keys`() {
        assertEquals(KeyAction.ToggleNotes, key(KeyCode.N))
        assertEquals(KeyAction.Hint, key(KeyCode.H))
        assertEquals(KeyAction.Pause, key(KeyCode.P))
        assertEquals(KeyAction.NewGame, key(KeyCode.F2))
        assertEquals(KeyAction.Undo, key(KeyCode.U))
        assertEquals(KeyAction.Redo, key(KeyCode.U, shift = true))
        assertNull(key(KeyCode.X))
        assertNull(key(KeyCode.F5))
    }

    @Test
    fun `the shortcut key takes only Z and Shift+Z`() {
        assertEquals(KeyAction.Undo, key(KeyCode.Z, shortcut = true))
        assertEquals(KeyAction.Redo, key(KeyCode.Z, shift = true, shortcut = true))
        assertNull(key(KeyCode.U, shortcut = true))
        assertNull(key(KeyCode.DIGIT1, shortcut = true))
        assertNull(key(KeyCode.N, shortcut = true))
        assertNull(key(KeyCode.Z))
    }

    @Test
    fun `other modifiers give nothing`() {
        assertNull(key(KeyCode.DIGIT1, other = true))
        assertNull(key(KeyCode.H, other = true))
        assertNull(key(KeyCode.Z, shortcut = true, other = true))
        assertNull(key(KeyCode.LEFT, other = true))
    }
}
