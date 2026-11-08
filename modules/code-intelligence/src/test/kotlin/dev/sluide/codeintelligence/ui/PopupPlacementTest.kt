package dev.sluide.codeintelligence.ui

import javafx.geometry.BoundingBox
import javafx.geometry.Point2D
import javafx.geometry.Rectangle2D
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where popups go on the screen, what keeps a hover popup, and what a popup does when the editor's lines move. */
class PopupPlacementTest {
    private val screen = Rectangle2D(0.0, 0.0, 1000.0, 800.0)

    @Test
    fun `a popup goes below its anchor, above when only there it fits, and stays on the screen`() {
        val anchor = BoundingBox(100.0, 300.0, 8.0, 16.0)
        assertEquals(Point2D(100.0, 316.0), popupPosition(anchor, 300.0, 200.0, screen))
        val low = BoundingBox(100.0, 700.0, 8.0, 16.0)
        assertEquals(Point2D(100.0, 500.0), popupPosition(low, 300.0, 200.0, screen))
        val right = BoundingBox(900.0, 100.0, 8.0, 16.0)
        assertEquals(700.0, popupPosition(right, 300.0, 200.0, screen).x)
        // Fits on neither side: the side with more room.
        assertEquals(316.0, popupPosition(anchor, 300.0, 900.0, screen).y)
        assertEquals(700.0 - 900.0, popupPosition(low, 300.0, 900.0, screen).y)
    }

    @Test
    fun `the hover popup stays while the mouse is on its anchor, the gap to it, or the popup`() {
        val anchor = BoundingBox(100.0, 300.0, 8.0, 16.0)
        val popup = BoundingBox(100.0, 316.0, 300.0, 200.0)
        assertTrue(keepsHover(104.0, 310.0, anchor, popup))
        assertTrue(keepsHover(111.0, 318.0, anchor, null), "within the margin")
        assertTrue(keepsHover(350.0, 400.0, anchor, popup))
        assertFalse(keepsHover(130.0, 308.0, anchor, popup), "the next characters of the line")
        assertFalse(keepsHover(104.0, 310.0, null, null))
    }

    @ParameterizedTest(name = "first line {0} -> {1}: {2}")
    @CsvSource(
        nullValues = ["null"],
        value = [
            "4, 4, PLACE_AGAIN",
            "4, 5, HIDE",
            "4, 0, HIDE",
            "4, null, HIDE",
            "null, null, HIDE",
            "null, 0, HIDE",
        ],
    )
    fun `the same first line places the popup again, another one or none means scrolled`(
        before: Int?,
        now: Int?,
        expected: String,
    ) {
        assertEquals(ShownLinesReaction.valueOf(expected), shownLinesReaction(before, now))
    }
}
