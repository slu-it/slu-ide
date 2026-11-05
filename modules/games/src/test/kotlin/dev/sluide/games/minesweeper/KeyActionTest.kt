package dev.sluide.games.minesweeper

import javafx.scene.input.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyActionTest {
    @Test
    fun `keys map to actions`() {
        assertEquals(KeyAction.Move(-1, 0), keyAction(KeyCode.LEFT))
        assertEquals(KeyAction.Move(1, 0), keyAction(KeyCode.RIGHT))
        assertEquals(KeyAction.Move(0, -1), keyAction(KeyCode.UP))
        assertEquals(KeyAction.Move(0, 1), keyAction(KeyCode.DOWN))
        assertEquals(KeyAction.Reveal, keyAction(KeyCode.SPACE))
        assertEquals(KeyAction.Reveal, keyAction(KeyCode.ENTER))
        assertEquals(KeyAction.Flag, keyAction(KeyCode.F))
        assertEquals(KeyAction.NewGame, keyAction(KeyCode.F2))
        assertEquals(KeyAction.Pause, keyAction(KeyCode.P))
        assertNull(keyAction(KeyCode.X))
    }

    @Test
    fun `keys with a modifier are left to the menu accelerators`() {
        assertNull(keyAction(KeyCode.W, hasModifier = true))
        assertNull(keyAction(KeyCode.P, hasModifier = true))
        assertNull(keyAction(KeyCode.LEFT, hasModifier = true))
    }
}
