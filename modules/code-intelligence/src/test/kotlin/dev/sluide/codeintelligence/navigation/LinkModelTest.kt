package dev.sluide.codeintelligence.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/** When the name under the mouse shows as a link: Shortcut held over a word while go to definition can be asked. */
class LinkModelTest {
    private var enabled = true
    private val shown = mutableListOf<IntRange?>()

    /** Words of four characters: 0..3, 4..7, … */
    private val model =
        LinkModel(
            object : LinkView {
                override fun enabled() = enabled

                override fun wordAt(offset: Int) = (offset / 4 * 4).takeIf { offset < 100 }?.let { it until it + 4 }

                override fun show(range: IntRange?) {
                    shown += range
                }
            },
        )

    @Test
    fun `Shortcut over a word underlines it, also when pressed after the mouse came, and releasing clears it`() {
        model.mouseMoved(1, shortcut = false)
        assertEquals(emptyList(), shown)
        model.keyChanged(down = true)
        assertEquals(listOf<IntRange?>(0 until 4), shown)
        model.mouseMoved(2, shortcut = true)
        assertEquals(1, shown.size, "the same word is not shown again")
        model.mouseMoved(5, shortcut = true)
        assertEquals(4 until 8, shown.last())
        model.keyChanged(down = false)
        assertEquals(null, shown.last())
    }

    @Test
    fun `moving off the words, leaving the text or typing clears it`() {
        model.mouseMoved(1, shortcut = true)
        model.mouseMoved(150, shortcut = true)
        assertEquals(listOf(0 until 4, null), shown)
        model.mouseMoved(1, shortcut = true)
        model.mouseExited()
        model.mouseMoved(1, shortcut = true)
        model.textChanged()
        assertEquals(listOf(0 until 4, null, 0 until 4, null, 0 until 4, null), shown)
    }

    @Test
    fun `nothing is underlined while go to definition cannot be asked`() {
        enabled = false
        model.mouseMoved(1, shortcut = true)
        assertEquals(emptyList(), shown)
        enabled = true
        model.mouseMoved(2, shortcut = true)
        assertEquals(listOf<IntRange?>(0 until 4), shown)
    }
}
