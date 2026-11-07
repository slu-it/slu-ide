package dev.sluide.games.orbitguard

import javafx.scene.input.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyActionTest {
    @Test
    fun `every key maps to its action`() {
        val expected =
            mapOf(
                KeyCode.LEFT to KeyAction.LEFT,
                KeyCode.A to KeyAction.LEFT,
                KeyCode.RIGHT to KeyAction.RIGHT,
                KeyCode.D to KeyAction.RIGHT,
                KeyCode.SPACE to KeyAction.FIRE,
                KeyCode.UP to KeyAction.FIRE,
                KeyCode.W to KeyAction.FIRE,
                KeyCode.P to KeyAction.PAUSE,
                KeyCode.F2 to KeyAction.NEW_GAME,
            )
        expected.forEach { (code, action) -> assertEquals(action, keyAction(code), "$code") }
    }

    @Test
    fun `a modifier turns every key into nothing`() {
        listOf(KeyCode.LEFT, KeyCode.D, KeyCode.SPACE, KeyCode.W, KeyCode.P, KeyCode.F2).forEach {
            assertNull(keyAction(it, hasModifier = true), "$it")
        }
    }

    @Test
    fun `other keys do nothing`() {
        listOf(KeyCode.ENTER, KeyCode.X, KeyCode.DOWN, KeyCode.S).forEach { assertNull(keyAction(it), "$it") }
    }
}
