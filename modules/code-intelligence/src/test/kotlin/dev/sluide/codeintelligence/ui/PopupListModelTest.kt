package dev.sluide.codeintelligence.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The list of a caret popup: selection, paging, updates. */
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
}
