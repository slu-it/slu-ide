package dev.sluide.codeintelligence.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The list of a caret popup: selection, paging, updates. */
internal class PopupListModelTest {
    private val model = PopupListModel<String>().apply { setItems(('a'..'z').map(Char::toString)) }

    companion object {
        @JvmStatic
        fun userChoices(): List<Arguments> =
            listOf<Pair<String, (PopupListModel<String>) -> Unit>>(
                "select" to { it.select(2) },
                "move" to { it.move(2) },
                "page" to { it.page(1, 2) },
                "first" to {
                    it.last()
                    it.first()
                },
                "last" to { it.last() },
            ).map { Arguments.of(it.first, it.second) }
    }

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
    fun `new items keep an item the user moved to, else select the first`() {
        model.move(3)
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

    @Test
    fun `a selection the user did not make does not survive re-ranking`() {
        val typed = PopupListModel<String>().apply { setItems(listOf("Content-Disposition", "Content-Type", "Accept")) }
        assertEquals("Content-Disposition", typed.selected)
        typed.setItems(listOf("Content-Type", "Content-Disposition"))
        assertEquals("Content-Type", typed.selected)
    }

    @Test
    fun `an item selected for the app is not the user's choice, also after the user moved`() {
        model.move(1)
        model.selectForApp(3)
        assertEquals("d", model.selected)
        model.setItems(listOf("b", "c", "d", "e"))
        assertEquals("b", model.selected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userChoices")
    fun `a click or a key that moves the selection is the user's choice`(
        name: String,
        choose: (PopupListModel<String>) -> Unit,
    ) {
        val list = PopupListModel<String>().apply { setItems(listOf("a", "b", "c", "d")) }
        choose(list)
        val chosen = checkNotNull(list.selected)
        list.setItems(listOf("x", chosen))
        assertEquals(chosen, list.selected, "kept after $name")
    }

    @Test
    fun `when the chosen item is gone the first is selected and the choice is forgotten`() {
        model.move(3)
        model.setItems(listOf("x", "y", "z"))
        assertEquals("x", model.selected)
        model.setItems(listOf("y", "x", "z"))
        assertEquals("y", model.selected, "the first again, the choice is gone")
    }

    @Test
    fun `a forgotten choice does not survive`() {
        model.move(3)
        model.forgetChoice()
        model.setItems(listOf("a", "d"))
        assertEquals("a", model.selected)
    }

    @Test
    fun `emptying the items forgets the choice`() {
        model.move(3)
        model.setItems(emptyList())
        model.setItems(listOf("a", "d"))
        model.setItems(listOf("d", "a"))
        assertEquals("d", model.selected)
    }

    @Test
    fun `emptying the items while keeping the index forgets the choice too`() {
        model.move(3)
        model.setItems(emptyList(), keepIndex = true)
        model.setItems(listOf("a", "d"), keepIndex = true)
        model.setItems(listOf("d", "a"))
        assertEquals("d", model.selected)
    }

    @Test
    fun `a choice of the first item stays as well`() {
        val list = PopupListModel<String>().apply { setItems(listOf("a", "b", "c")) }
        list.select(0)
        list.setItems(listOf("a", "b", "c"))
        list.setItems(listOf("b", "a", "c"))
        assertEquals("a", list.selected)
    }

    @Test
    fun `an empty list ignores a selection for the app`() {
        val empty = PopupListModel<String>()
        empty.selectForApp(2)
        assertEquals(-1, empty.selectedIndex)
    }

    @Test
    fun `keeping the index keeps the position though the items differ`() {
        model.selectForApp(3)
        model.setItems(listOf("A", "B", "C", "D", "E"), keepIndex = true)
        assertEquals("D", model.selected)
        model.setItems(listOf("x", "y"), keepIndex = true)
        assertEquals("y", model.selected, "clamped to the last item")
        model.setItems(emptyList(), keepIndex = true)
        assertEquals(-1, model.selectedIndex)
    }
}
