package dev.sluide.games.common.ui

import javafx.scene.input.KeyCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameZoomInputTest {
    @ParameterizedTest
    @CsvSource(
        "PLUS, ZOOM_IN",
        "EQUALS, ZOOM_IN",
        "ADD, ZOOM_IN",
        "MINUS, ZOOM_OUT",
        "SUBTRACT, ZOOM_OUT",
        "DIGIT0, FIT",
        "NUMPAD0, FIT",
    )
    fun `the zoom keys with Shortcut zoom`(
        code: KeyCode,
        expected: String,
    ) {
        assertEquals(expected, gameZoomKey(code, shortcut = true)?.name)
    }

    @ParameterizedTest
    @EnumSource(KeyCode::class, names = ["PLUS", "EQUALS", "ADD", "MINUS", "SUBTRACT", "DIGIT0", "NUMPAD0"])
    fun `the zoom keys without Shortcut do nothing`(code: KeyCode) {
        assertNull(gameZoomKey(code, shortcut = false))
    }

    @ParameterizedTest
    @EnumSource(KeyCode::class, names = ["DIGIT9", "NUMPAD9", "DIGIT1", "P", "UP"])
    fun `other keys with Shortcut do nothing, Shortcut+9 is left to Git Log`(code: KeyCode) {
        assertNull(gameZoomKey(code, shortcut = true))
    }

    @Test
    fun `the wheel zooms only with Shortcut, by a tenth per notch`() {
        assertEquals(1.1, checkNotNull(wheelZoomFactor(40.0, shortcut = true)), 1e-9)
        assertEquals(1 / 1.1, checkNotNull(wheelZoomFactor(-40.0, shortcut = true)), 1e-9)
        assertNull(wheelZoomFactor(40.0, shortcut = false))
        assertNull(wheelZoomFactor(0.0, shortcut = true))
    }
}
