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
    fun `a search popup goes centred at the top of its area, staying on the screen`() {
        val editor = BoundingBox(200.0, 100.0, 600.0, 500.0)
        assertEquals(Point2D(240.0, 108.0), searchPopupPosition(editor, 520.0, screen))
        val atRightEdge = BoundingBox(700.0, 100.0, 600.0, 500.0)
        assertEquals(480.0, searchPopupPosition(atRightEdge, 520.0, screen).x)
        val narrowScreen = Rectangle2D(0.0, 0.0, 400.0, 800.0)
        assertEquals(0.0, searchPopupPosition(editor, 520.0, narrowScreen).x, "never past the left edge")
    }

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
    fun `a popup preferring above goes above its anchor, below only when it fits there but not above`() {
        val anchor = BoundingBox(100.0, 300.0, 8.0, 16.0)
        assertEquals(100.0, popupPosition(anchor, 300.0, 200.0, screen, preferAbove = true).y)
        val high = BoundingBox(100.0, 50.0, 8.0, 16.0)
        assertEquals(66.0, popupPosition(high, 300.0, 200.0, screen, preferAbove = true).y)
        // Fits on neither side: the side with more room.
        assertEquals(500.0 - 900.0, popupPosition(BoundingBox(100.0, 500.0, 8.0, 16.0), 300.0, 900.0, screen, true).y)
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

    // A screen that does not start at 0,0, as the second monitor of a layout: its visual bounds are x 100..1100,
    // y 50..850, so the room above an anchor is its minY less 50 and the room below 834 less its minY (16 high).
    private val second = Rectangle2D(100.0, 50.0, 1000.0, 800.0)

    private fun anchorAt(y: Double) = BoundingBox(300.0, y, 8.0, 16.0)

    @ParameterizedTest(name = "anchor y {0}, height {1}, prefer above {2}: above {3}")
    @CsvSource(
        // Room above 392, below 392 (equal): the popup fits on neither side; equal room goes below, or above if preferred.
        "442, 500, false, false",
        "442, 500, true, true",
        // Height exactly the room below (234): it fits there, so it stays below although there is more room above.
        "600, 234, false, false",
        // Height exactly the room above (250): it fits there; preferred above, it goes there, else below (room below 534).
        "300, 250, true, true",
        "300, 250, false, false",
        // Room above 380, below 404, neither fits: below has more room, though the room above measured from y 0 is more.
        "430, 900, false, false",
        "430, 900, true, false",
        // Room above 400, below 384: above has more room.
        "450, 900, false, true",
        "450, 900, true, true",
        // Room below 134 is too small: above, whatever the preference only when it fits (room above 650).
        "700, 140, false, true",
        "700, 140, true, true",
        // Room above 50 is too small for 100, room below 734 fits: below, also when above is preferred.
        "100, 100, true, false",
        "100, 100, false, false",
    )
    fun `a popup chooses its side by the room on a screen that starts away from the origin`(
        y: Double,
        height: Double,
        preferAbove: Boolean,
        above: Boolean,
    ) {
        val anchor = anchorAt(y)
        val expected = if (above) y - height else y + 16
        assertEquals(expected, popupPosition(anchor, 200.0, height, second, preferAbove).y)
    }

    @Test
    fun `a popup is moved right to the left edge of a screen that starts away from the origin`() {
        assertEquals(100.0, popupPosition(BoundingBox(50.0, 300.0, 8.0, 16.0), 200.0, 100.0, second).x)
        assertEquals(1100.0 - 200.0, popupPosition(BoundingBox(1050.0, 300.0, 8.0, 16.0), 200.0, 100.0, second).x)
        assertEquals(150.0, popupPosition(BoundingBox(150.0, 300.0, 8.0, 16.0), 200.0, 100.0, second).x)
    }

    @Test
    fun `a popup wider than the screen starts at its left edge`() {
        assertEquals(100.0, popupPosition(BoundingBox(300.0, 300.0, 8.0, 16.0), 1500.0, 100.0, second).x)
        assertEquals(100.0, searchPopupPosition(BoundingBox(300.0, 300.0, 600.0, 400.0), 1500.0, second).x)
    }

    @Test
    fun `a search popup stays inside a screen that starts away from the origin`() {
        val left = BoundingBox(0.0, 0.0, 400.0, 500.0)
        assertEquals(Point2D(100.0, 50.0), searchPopupPosition(left, 520.0, second), "left and top edge")
        val inside = BoundingBox(300.0, 200.0, 600.0, 500.0)
        assertEquals(Point2D(340.0, 208.0), searchPopupPosition(inside, 520.0, second))
        val aboveTop = BoundingBox(300.0, 42.0, 600.0, 500.0)
        assertEquals(50.0, searchPopupPosition(aboveTop, 520.0, second).y, "exactly at the top edge")
        assertEquals(51.0, searchPopupPosition(BoundingBox(300.0, 43.0, 600.0, 500.0), 520.0, second).y)
        assertEquals(1100.0 - 520.0, searchPopupPosition(BoundingBox(900.0, 200.0, 600.0, 500.0), 520.0, second).x)
    }

    @ParameterizedTest(name = "({0}, {1}): {2}")
    @CsvSource(
        // The anchor is x 100..108, y 300..316; the margin is 4 on every side, edges included.
        "96, 300, true",
        "95.9, 300, false",
        "112, 300, true",
        "112.1, 300, false",
        "100, 296, true",
        "100, 295.9, false",
        "100, 320, true",
        "100, 320.1, false",
        "104, 308, true",
    )
    fun `the hover popup stays on the margin of its anchor up to its edge, not past it`(
        x: Double,
        y: Double,
        keeps: Boolean,
    ) {
        assertEquals(keeps, keepsHover(x, y, BoundingBox(100.0, 300.0, 8.0, 16.0), null))
    }
}
