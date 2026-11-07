package dev.sluide.games.blockbreaker

import javafx.scene.input.KeyCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class KeyActionTest {
    @ParameterizedTest
    @CsvSource(
        "LEFT,LEFT",
        "A,LEFT",
        "RIGHT,RIGHT",
        "D,RIGHT",
        "SPACE,LAUNCH",
        "UP,LAUNCH",
        "W,LAUNCH",
        "P,PAUSE",
        "F2,NEW_GAME",
    )
    fun `every key maps to its action`(
        code: KeyCode,
        action: KeyAction,
    ) {
        assertEquals(action, keyAction(code))
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode::class, names = ["LEFT", "D", "SPACE", "W", "P", "F2"])
    fun `a modifier turns every key into nothing`(code: KeyCode) {
        assertNull(keyAction(code, hasModifier = true))
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode::class, names = ["ENTER", "X", "DOWN", "S"])
    fun `other keys do nothing`(code: KeyCode) {
        assertNull(keyAction(code))
    }
}
