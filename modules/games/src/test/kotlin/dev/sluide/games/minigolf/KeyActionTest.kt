package dev.sluide.games.minigolf

import javafx.scene.input.KeyCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class KeyActionTest {
    @ParameterizedTest
    @CsvSource(
        "P,PAUSE",
        "F2,NEW_GAME",
        "ESCAPE,CANCEL",
        "SPACE,CONTINUE",
        "ENTER,CONTINUE",
    )
    fun `every key maps to its action`(
        code: KeyCode,
        action: KeyAction,
    ) {
        assertEquals(action, keyAction(code))
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode::class, names = ["P", "F2", "ESCAPE", "SPACE", "ENTER"])
    fun `a modifier turns every key into nothing`(code: KeyCode) {
        assertNull(keyAction(code, hasModifier = true))
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode::class, names = ["LEFT", "X", "W", "TAB"])
    fun `other keys do nothing`(code: KeyCode) {
        assertNull(keyAction(code))
    }
}
