package dev.sluide.codeintelligence.ui

import javafx.scene.Group
import javafx.scene.Node
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * When the hover popup asks its providers and hides, and how their answers are put together: in the providers' order
 * as they arrive, nothing for empty answers, late answers of a dismissed question ignored and the providers told.
 */
class HoverModelTest {
    /** A provider whose answers the test gives; records the offsets asked and the questions closed. */
    private class TestProvider : HoverProvider {
        val asked = mutableListOf<Int>()
        val pending = mutableListOf<(List<Node>?) -> Unit>()
        var closed = 0

        override fun hover(
            offset: Int,
            done: (List<Node>?) -> Unit,
        ): AutoCloseable {
            asked += offset
            pending += done
            return AutoCloseable { closed++ }
        }

        fun answer(vararg nodes: Node) = pending.removeFirst()(nodes.toList())
    }

    private class RecordingView : HoverView {
        var shown: Pair<Int, List<Node>>? = null
        var hides = 0
        var refuse = false

        override fun show(
            offset: Int,
            sections: List<Node>,
        ): Boolean {
            if (refuse) return false
            shown = offset to sections
            return true
        }

        override fun hide() {
            hides++
            shown = null
        }
    }

    /** The scheduled rests: the test runs them. */
    private val timers = mutableListOf<Pair<Duration, () -> Unit>>()
    private val cancelled = mutableListOf<Duration>()

    private fun schedule(
        delay: Duration,
        action: () -> Unit,
    ): AutoCloseable {
        val entry = delay to action
        timers += entry
        return AutoCloseable {
            if (timers.remove(entry)) cancelled += delay
        }
    }

    private fun rest() = timers.removeFirst().second()

    private val first = TestProvider()
    private val second = TestProvider()
    private val view = RecordingView()
    private val model = HoverModel({ listOf(first, second) }, view, 500.milliseconds, ::schedule)

    @Test
    fun `a rest on a character asks every provider and shows their sections in the providers' order as they arrive`() {
        model.mouseMoved(7, keeps = false)
        assertEquals(500.milliseconds, timers.single().first)
        assertEquals(emptyList(), first.asked, "nothing before the rest")
        rest()
        assertEquals(listOf(7), first.asked)
        assertEquals(listOf(7), second.asked)

        val type = Group()
        val problem = Group()
        second.answer(type)
        assertEquals(7 to listOf<Node>(type), view.shown)
        first.answer(problem)
        assertEquals(7 to listOf<Node>(problem, type), view.shown)
        assertTrue(model.isShowing)
    }

    @Test
    fun `empty answers show nothing`() {
        model.mouseMoved(3, keeps = false)
        rest()
        first.answer()
        second.pending.removeFirst()(null)
        assertNull(view.shown)
        assertFalse(model.isShowing)
    }

    @Test
    fun `moving within the character keeps the rest, moving on starts a new one and drops the old question`() {
        model.mouseMoved(3, keeps = false)
        model.mouseMoved(3, keeps = false)
        assertEquals(1, timers.size)
        model.mouseMoved(4, keeps = false)
        assertEquals(listOf(500.milliseconds), cancelled)
        rest()
        assertEquals(listOf(4), first.asked)

        model.mouseMoved(5, keeps = false)
        assertEquals(1, first.closed, "the question for 4 is closed")
        first.answer(Group())
        assertNull(view.shown, "its late answer is ignored")
    }

    @Test
    fun `a shown popup stays on its anchor and on the way to it, and hides on another character`() {
        model.mouseMoved(3, keeps = false)
        rest()
        first.answer(Group())
        model.mouseMoved(9, keeps = true)
        model.mouseMoved(null, keeps = true)
        assertTrue(model.isShowing)

        model.mouseMoved(9, keeps = false)
        assertFalse(model.isShowing)
        assertEquals(1, view.hides)
        assertEquals(1, timers.size, "the rest on the new character starts")
    }

    @Test
    fun `dismissing hides, closes the question and stops a waiting rest`() {
        model.mouseMoved(3, keeps = false)
        rest()
        first.answer(Group())
        model.mouseMoved(3, keeps = false)
        model.dismiss()
        assertFalse(model.isShowing)
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)

        model.mouseMoved(6, keeps = false)
        model.dismiss()
        assertTrue(timers.isEmpty())
        model.mouseMoved(6, keeps = false)
        assertEquals(1, timers.size, "the same character rests again after a dismiss")
    }

    @Test
    fun `F1 asks at once for the caret, and only with providers`() {
        assertTrue(model.showAt(12))
        assertEquals(listOf(12), first.asked)
        assertTrue(timers.isEmpty())

        val none = HoverModel({ emptyList() }, view, 500.milliseconds, ::schedule)
        assertFalse(none.showAt(12))
    }

    @Test
    fun `a popup that could not show or hid by itself counts as hidden`() {
        view.refuse = true
        model.showAt(2)
        first.answer(Group())
        assertFalse(model.isShowing)

        view.refuse = false
        model.showAt(2)
        first.answer(Group())
        model.hiddenByView()
        assertFalse(model.isShowing)
        assertEquals(2, first.closed)
    }

    @Test
    fun `a provider that throws leaves the others`() {
        val broken = HoverProvider { _, _ -> error("broken") }
        val query = HoverQuery(listOf(broken, first), 1) { sections -> view.show(1, sections) }
        first.answer(Group())
        assertEquals(1, view.shown?.second?.size)
        query.close()
    }
}
