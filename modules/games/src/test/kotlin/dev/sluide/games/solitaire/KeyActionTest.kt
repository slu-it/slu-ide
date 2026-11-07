package dev.sluide.games.solitaire

import javafx.scene.input.KeyCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyActionTest {
    @ParameterizedTest(name = "{0} shift={1} shortcut={2} -> {3}")
    @MethodSource("keys")
    internal fun `keys map to their actions`(
        code: KeyCode,
        shift: Boolean,
        shortcut: Boolean,
        expected: KeyAction?,
    ) {
        assertEquals(expected, keyAction(code, shift, shortcut, otherModifier = false))
    }

    @ParameterizedTest
    @EnumSource(KeyCode::class, names = ["Z", "U", "D", "SPACE", "ENTER", "LEFT", "F2"])
    fun `another modifier gives nothing`(code: KeyCode) {
        assertNull(keyAction(code, shift = false, shortcut = false, otherModifier = true))
        assertNull(keyAction(code, shift = true, shortcut = true, otherModifier = true))
    }

    companion object {
        private fun key(
            code: KeyCode,
            expected: KeyAction?,
            shift: Boolean = false,
            shortcut: Boolean = false,
        ) = Arguments.of(code, shift, shortcut, expected)

        private val up = KeyAction.Navigate(Direction.UP)
        private val down = KeyAction.Navigate(Direction.DOWN)
        private val left = KeyAction.Navigate(Direction.LEFT)
        private val right = KeyAction.Navigate(Direction.RIGHT)

        @JvmStatic
        fun keys(): List<Arguments> =
            listOf(
                key(KeyCode.UP, up),
                key(KeyCode.DOWN, down),
                key(KeyCode.LEFT, left),
                key(KeyCode.RIGHT, right),
                key(KeyCode.D, KeyAction.Stock),
                key(KeyCode.W, null),
                key(KeyCode.A, null),
                key(KeyCode.S, null),
                key(KeyCode.SPACE, KeyAction.PickOrDrop),
                key(KeyCode.ENTER, KeyAction.Play),
                key(KeyCode.ESCAPE, KeyAction.Cancel),
                key(KeyCode.F, KeyAction.ToFoundation),
                key(KeyCode.F2, KeyAction.NewGame),
                key(KeyCode.U, KeyAction.Undo),
                key(KeyCode.U, KeyAction.Redo, shift = true),
                key(KeyCode.Z, KeyAction.Undo, shortcut = true),
                key(KeyCode.Z, KeyAction.Redo, shift = true, shortcut = true),
                key(KeyCode.H, KeyAction.Hint),
                key(KeyCode.P, KeyAction.Pause),
                key(KeyCode.Z, null),
                key(KeyCode.Q, null),
                key(KeyCode.U, null, shortcut = true),
                key(KeyCode.H, null, shortcut = true),
            )
    }
}
