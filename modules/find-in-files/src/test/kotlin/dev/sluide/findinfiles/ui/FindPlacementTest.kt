package dev.sluide.findinfiles.ui

import javafx.geometry.BoundingBox
import javafx.geometry.Rectangle2D
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

internal class FindPlacementTest {
    private val screen = Rectangle2D(0.0, 0.0, 1920.0, 1080.0)

    @ParameterizedTest
    @CsvSource("500,640", "900,640", "1000,700", "1428,999.6", "1600,1000", "3000,1000")
    fun `width is 70 percent clamped to 640 to 1000`(
        area: Double,
        expected: Double,
    ) {
        assertEquals(expected, findPopupWidth(area), 1e-9)
    }

    @Test
    fun `centred in the area, 8 px below its top`() {
        val position = findPopupPosition(BoundingBox(100.0, 200.0, 1000.0, 600.0), 700.0, screen)
        assertEquals(250.0, position.x)
        assertEquals(208.0, position.y)
    }

    @Test
    fun `clamped at the left, right and top of the screen`() {
        val narrow = 640.0
        assertEquals(0.0, findPopupPosition(BoundingBox(-300.0, 50.0, 700.0, 600.0), narrow, screen).x)
        assertEquals(1920.0 - narrow, findPopupPosition(BoundingBox(1500.0, 50.0, 700.0, 600.0), narrow, screen).x)
        assertEquals(0.0, findPopupPosition(BoundingBox(100.0, -50.0, 700.0, 600.0), narrow, screen).y)
    }
}
