package dev.sluide.pdf.view

import javafx.scene.input.KeyCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollKeysTest {
    @ParameterizedTest
    @CsvSource(
        "UP, false, 0, -40",
        "DOWN, false, 0, 40",
        "LEFT, false, -40, 0",
        "RIGHT, false, 40, 0",
        "PAGE_UP, false, 0, -450",
        "PAGE_DOWN, false, 0, 450",
        "SPACE, false, 0, 450",
        "SPACE, true, 0, -450",
        "HOME, false, 0, -Infinity",
        "END, false, 0, Infinity",
    )
    fun `scroll keys scroll a line, most of the viewport or to an end`(
        code: KeyCode,
        shift: Boolean,
        x: Double,
        y: Double,
    ) {
        assertEquals(x to y, scrollKeyDelta(code, shift, 500.0))
    }

    @ParameterizedTest
    @EnumSource(names = ["UP", "DOWN", "LEFT", "RIGHT", "PAGE_UP", "PAGE_DOWN", "HOME", "END"])
    fun `with Shift only Space scrolls`(code: KeyCode) {
        assertNull(scrollKeyDelta(code, shift = true, viewHeight = 500.0))
    }

    @Test
    fun `other keys do not scroll`() {
        assertNull(scrollKeyDelta(KeyCode.A, shift = false, viewHeight = 500.0))
        assertNull(scrollKeyDelta(KeyCode.ENTER, shift = false, viewHeight = 500.0))
    }
}
