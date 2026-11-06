package dev.sluide.games.doubles

import javafx.scene.input.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyActionTest {
    @Test
    fun `arrows and WASD move`() {
        val expected =
            mapOf(
                KeyCode.UP to Direction.UP,
                KeyCode.W to Direction.UP,
                KeyCode.DOWN to Direction.DOWN,
                KeyCode.S to Direction.DOWN,
                KeyCode.LEFT to Direction.LEFT,
                KeyCode.A to Direction.LEFT,
                KeyCode.RIGHT to Direction.RIGHT,
                KeyCode.D to Direction.RIGHT,
            )
        expected.forEach { (code, direction) -> assertEquals(KeyAction.Move(direction), keyAction(code), "$code") }
    }

    @Test
    fun `U and Backspace undo, F2 starts a new game`() {
        assertEquals(KeyAction.Undo, keyAction(KeyCode.U))
        assertEquals(KeyAction.Undo, keyAction(KeyCode.BACK_SPACE))
        assertEquals(KeyAction.NewGame, keyAction(KeyCode.F2))
    }

    @Test
    fun `other keys and modified keys do nothing`() {
        assertNull(keyAction(KeyCode.ENTER))
        assertNull(keyAction(KeyCode.X))
        listOf(KeyCode.UP, KeyCode.W, KeyCode.U, KeyCode.F2).forEach { assertNull(keyAction(it, hasModifier = true)) }
    }
}
