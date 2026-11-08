package dev.sluide.codeintelligence.ui

import javafx.geometry.BoundingBox
import javafx.geometry.Point2D
import javafx.geometry.Rectangle2D
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The list of a caret popup (selection, paging, updates) and where popups go on the screen. */
class PopupListModelTest {
    private val model = PopupListModel<String>().apply { setItems(('a'..'z').map(Char::toString)) }

    @Test
    fun `a list selects its first item, steps wrap around and pages stop at the ends`() {
        assertEquals("a", model.selected)
        model.move(-1)
        assertEquals("z", model.selected)
        model.move(1)
        assertEquals("a", model.selected)
        model.page(1, 10)
        assertEquals("k", model.selected)
        model.page(5, 10)
        assertEquals("z", model.selected)
        model.page(-1, 10)
        assertEquals("p", model.selected)
        model.first()
        assertEquals("a", model.selected)
        model.last()
        assertEquals("z", model.selected)
        model.select(99)
        assertEquals("z", model.selected)
    }

    @Test
    fun `new items keep the selected item if it is still there, else select the first`() {
        model.select(3)
        model.setItems(listOf("b", "c", "d", "e"))
        assertEquals("d", model.selected)
        assertEquals(2, model.selectedIndex)
        model.setItems(listOf("x", "y"))
        assertEquals("x", model.selected)
        model.setItems(emptyList())
        assertNull(model.selected)
        assertEquals(-1, model.selectedIndex)
        model.move(1)
        model.last()
        assertEquals(-1, model.selectedIndex, "nothing to select")
        model.setItems(listOf("q"))
        assertEquals("q", model.selected)
    }

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
}
