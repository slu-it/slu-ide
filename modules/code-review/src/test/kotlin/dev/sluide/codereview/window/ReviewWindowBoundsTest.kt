package dev.sluide.codereview.window

import javafx.geometry.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewWindowBoundsTest {
    /** An ultra-wide screen with the IDE window on its left half. */
    private val area = Rectangle2D(0.0, 0.0, 5120.0, 1440.0)
    private val owner = Rectangle2D(100.0, 50.0, 2000.0, 1200.0)

    @Test
    fun `the first window is 90 percent of the IDE window, centred on it, not of the screen`() {
        assertEquals(Rectangle2D(200.0, 110.0, 1800.0, 1080.0), reviewWindowBounds(area, owner, 0))
    }

    @Test
    fun `a small IDE window still gets a review window of the minimum size, centred on it`() {
        val small = Rectangle2D(1000.0, 400.0, 500.0, 400.0)

        assertEquals(Rectangle2D(800.0, 300.0, 900.0, 600.0), reviewWindowBounds(area, small, 0))
    }

    @Test
    fun `a window that would leave the screen is moved inside it and never larger than it`() {
        val screen = Rectangle2D(0.0, 0.0, 800.0, 500.0)
        val offScreen = Rectangle2D(-300.0, 200.0, 1000.0, 700.0)

        assertEquals(Rectangle2D(0.0, 0.0, 800.0, 500.0), reviewWindowBounds(screen, offScreen, 0))
        val atRight = Rectangle2D(4800.0, 100.0, 1000.0, 700.0)
        assertEquals(Rectangle2D(4220.0, 135.0, 900.0, 630.0), reviewWindowBounds(area, atRight, 0))
    }

    @Test
    fun `a screen with a fractional origin smaller than the minimum size does not throw`() {
        val scaled = Rectangle2D(0.8, 0.8, 768.0, 544.0)

        assertEquals(
            Rectangle2D(0.8, 0.8, 768.0, 544.0),
            reviewWindowBounds(scaled, Rectangle2D(0.8, 0.8, 768.0, 544.0), 3),
        )
    }

    @Test
    fun `each further window is 30 px right and down`() {
        val first = reviewWindowBounds(area, owner, 0)
        val second = reviewWindowBounds(area, owner, 1)

        assertEquals(first.minX + 30, second.minX)
        assertEquals(first.minY + 30, second.minY)
        assertEquals(first.width, second.width)
    }

    @Test
    fun `the cascade starts again before a window would leave the screen`() {
        for (index in 0..40) {
            val bounds = reviewWindowBounds(area, owner, index)
            assertTrue(bounds.maxX <= area.maxX && bounds.maxY <= area.maxY, "window $index inside the area")
        }
        // 1440 - 110 - 1080 = 250 px below the window: steps 0..8, then back to the centre.
        assertEquals(reviewWindowBounds(area, owner, 0), reviewWindowBounds(area, owner, 9))
    }
}
