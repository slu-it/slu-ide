package dev.sluide.codereview.window

import javafx.geometry.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewWindowBoundsTest {
    private val area = Rectangle2D(100.0, 50.0, 1000.0, 800.0)

    @Test
    fun `the first window is 80 percent of the area, centred`() {
        assertEquals(Rectangle2D(200.0, 130.0, 800.0, 640.0), reviewWindowBounds(area, 0))
    }

    @Test
    fun `each further window is 30 px right and down`() {
        val first = reviewWindowBounds(area, 0)
        val second = reviewWindowBounds(area, 1)

        assertEquals(first.minX + 30, second.minX)
        assertEquals(first.minY + 30, second.minY)
        assertEquals(first.width, second.width)
    }

    @Test
    fun `the cascade starts again before a window would leave the area`() {
        for (index in 0..40) {
            val bounds = reviewWindowBounds(area, index)
            assertTrue(bounds.maxX <= area.maxX && bounds.maxY <= area.maxY, "window $index inside the area")
        }
        // 80 px of margin: steps 0..2, then back to the centre.
        assertEquals(reviewWindowBounds(area, 0), reviewWindowBounds(area, 3))
    }
}
