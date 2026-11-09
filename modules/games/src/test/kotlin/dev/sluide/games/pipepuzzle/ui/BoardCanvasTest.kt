package dev.sluide.games.pipepuzzle.ui

import javafx.scene.input.MouseButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardCanvasTest {
    @Test
    fun `primary turns clockwise, secondary and Ctrl with primary counter-clockwise`() {
        assertEquals(true, turnClockwise(MouseButton.PRIMARY, false))
        assertEquals(false, turnClockwise(MouseButton.PRIMARY, true))
        assertEquals(false, turnClockwise(MouseButton.SECONDARY, false))
        assertEquals(false, turnClockwise(MouseButton.SECONDARY, true))
    }

    @Test
    fun `other buttons do not turn`() {
        assertNull(turnClockwise(MouseButton.MIDDLE, false))
        assertNull(turnClockwise(MouseButton.NONE, false))
    }
}
