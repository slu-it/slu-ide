package dev.sluide.previews.preview

import javafx.scene.input.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZoomKeyActionTest {
    @Test
    fun `the zoom keys map to their actions`() {
        val expected =
            mapOf(
                KeyCode.PLUS to ZoomKeyAction.ZOOM_IN,
                KeyCode.EQUALS to ZoomKeyAction.ZOOM_IN,
                KeyCode.ADD to ZoomKeyAction.ZOOM_IN,
                KeyCode.MINUS to ZoomKeyAction.ZOOM_OUT,
                KeyCode.SUBTRACT to ZoomKeyAction.ZOOM_OUT,
                KeyCode.DIGIT0 to ZoomKeyAction.ACTUAL_SIZE,
                KeyCode.NUMPAD0 to ZoomKeyAction.ACTUAL_SIZE,
                KeyCode.DIGIT9 to ZoomKeyAction.FIT,
                KeyCode.NUMPAD9 to ZoomKeyAction.FIT,
            )
        for (code in KeyCode.entries) {
            assertEquals(expected[code], zoomKeyAction(code, fits = true), "$code")
        }
    }

    @Test
    fun `Fit is only a key of targets that fit`() {
        assertNull(zoomKeyAction(KeyCode.DIGIT9, fits = false))
        assertNull(zoomKeyAction(KeyCode.NUMPAD9, fits = false))
        assertEquals(ZoomKeyAction.ZOOM_IN, zoomKeyAction(KeyCode.EQUALS, fits = false))
    }
}
