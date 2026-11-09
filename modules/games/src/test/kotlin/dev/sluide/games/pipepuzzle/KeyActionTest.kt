package dev.sluide.games.pipepuzzle

import javafx.scene.input.KeyCode
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

    @Test
    fun `arrows move`() {
        assertEquals(KeyAction.Move(-1, 0), key(KeyCode.UP))
        assertEquals(KeyAction.Move(1, 0), key(KeyCode.DOWN))
        assertEquals(KeyAction.Move(0, -1), key(KeyCode.LEFT))
        assertEquals(KeyAction.Move(0, 1), key(KeyCode.RIGHT))
    }

    @Test
    fun `space and enter turn clockwise and with shift counter-clockwise`() {
        listOf(KeyCode.SPACE, KeyCode.ENTER).forEach {
            assertEquals(KeyAction.Turn(true), key(it))
            assertEquals(KeyAction.Turn(false), key(it, shift = true))
        }
    }

    @Test
    fun `letter and function keys`() {
        assertEquals(KeyAction.Hint, key(KeyCode.H))
        assertEquals(KeyAction.Restart, key(KeyCode.R))
        assertEquals(KeyAction.Pause, key(KeyCode.P))
        assertEquals(KeyAction.NewGame, key(KeyCode.F2))
        assertNull(key(KeyCode.X))
        assertNull(key(KeyCode.W))
    }

    @Test
    fun `the shortcut key and other modifiers give nothing`() {
        listOf(KeyCode.H, KeyCode.SPACE, KeyCode.LEFT, KeyCode.F2).forEach {
            assertNull(key(it, shortcut = true))
            assertNull(key(it, other = true))
        }
    }
}
