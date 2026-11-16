package dev.sluide.editorarea

import javafx.beans.InvalidationListener
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.collections.ListChangeListener
import javafx.scene.Group
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class EditorTabsCloseRequestTest {
    private val tabs = EditorTabs()

    /** Counts the listeners that are attached, so a subscription that is not dropped shows. */
    private class CountingFlag(
        initial: Boolean = false,
    ) : SimpleBooleanProperty(initial) {
        var attached = 0

        override fun addListener(listener: ChangeListener<in Boolean>?) {
            attached++
            super.addListener(listener)
        }

        override fun removeListener(listener: ChangeListener<in Boolean>?) {
            attached--
            super.removeListener(listener)
        }

        override fun addListener(listener: InvalidationListener?) {
            attached++
            super.addListener(listener)
        }

        override fun removeListener(listener: InvalidationListener?) {
            attached--
            super.removeListener(listener)
        }
    }

    private class SelfClosingTab(
        name: String,
        override val closeRequested: CountingFlag = CountingFlag(),
    ) : EditorTab {
        var disposed = 0
        var onDispose: () -> Unit = {}

        override val title = SimpleStringProperty(name)
        override val tooltip = name
        override val content = Group()

        override fun dispose() {
            disposed++
            onDispose()
        }
    }

    private fun names() = tabs.tabs.map { it.title.value }

    /** Built in a function of its own, so no local slot of the test keeps the editor tabs reachable. */
    private fun editorTabsWithPlainTab(): WeakReference<EditorTabs> {
        val plain =
            object : EditorTab {
                override val title = SimpleStringProperty("plain")
                override val tooltip = "plain"
                override val content = Group()
            }
        val area = EditorTabs()
        area.open(plain)
        return WeakReference(area)
    }

    @Test
    fun `a tab with the default closeRequested does not keep its editor tabs alive`() {
        // The default is one shared constant: a listener on it would hold every window's tabs forever.
        val area = editorTabsWithPlainTab()

        // Poll with a deadline: one System.gc() is only a request.
        val deadline = System.nanoTime() + 5_000_000_000
        while (area.get() != null && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(10)
        }

        assertNull(area.get())
    }

    @Test
    fun `a tab whose closeRequested turns true is closed, disposed once, and its neighbour selected`() {
        val a = SelfClosingTab("a")
        val b = SelfClosingTab("b")
        val c = SelfClosingTab("c")
        listOf(a, b, c).forEach { tabs.open(it) }
        tabs.select(b)

        b.closeRequested.set(true)

        assertEquals(listOf("a", "c"), names())
        assertEquals(1, b.disposed)
        assertSame(c, tabs.selected.value)
    }

    @Test
    fun `a tab whose closeRequested is true when opened is closed after the opening`() {
        val a = SelfClosingTab("a")
        val b = SelfClosingTab("b", CountingFlag(true))
        tabs.open(a)
        val seen = mutableListOf<List<String>>()
        tabs.tabs.addListener(ListChangeListener { seen += names() })

        tabs.open(b)

        assertEquals(listOf("a"), names())
        assertEquals(listOf(listOf("a", "b"), listOf("a")), seen)
        assertEquals(1, b.disposed)
        assertSame(a, tabs.selected.value)
    }

    @Test
    fun `closeRequested turning true after the tab was closed does nothing`() {
        val a = SelfClosingTab("a")
        tabs.open(a)
        tabs.close(a)

        a.closeRequested.set(true)

        assertEquals(1, a.disposed)
        assertEquals(0, a.closeRequested.attached)
    }

    @Test
    fun `a replacement is watched and the replaced tab no longer is`() {
        val old = SelfClosingTab("old")
        val replacement = SelfClosingTab("new")
        tabs.open(old)

        tabs.replace(old, replacement)

        assertEquals(0, old.closeRequested.attached)
        replacement.closeRequested.set(true)
        assertEquals(emptyList(), names())
        assertEquals(1, replacement.disposed)
    }

    @Test
    fun `a replacement already requesting close is closed once, after the replace is done`() {
        val old = SelfClosingTab("old")
        val replacement = SelfClosingTab("new", CountingFlag(true))
        tabs.open(old)
        var oldDisposedWhenReplacementDisposed = -1
        replacement.onDispose = { oldDisposedWhenReplacementDisposed = old.disposed }
        var afterDispose = 0
        tabs.afterDispose = { afterDispose++ }
        val seen = mutableListOf<List<String>>()
        tabs.tabs.addListener(ListChangeListener { seen += names() })

        tabs.replace(old, replacement)

        assertEquals(emptyList(), names())
        assertEquals(listOf(listOf("new"), emptyList()), seen)
        assertEquals(1, old.disposed)
        assertEquals(1, replacement.disposed)
        assertEquals(1, oldDisposedWhenReplacementDisposed)
        assertEquals(2, afterDispose)
    }

    @Test
    fun `a moved tab is still watched`() {
        val a = SelfClosingTab("a")
        val b = SelfClosingTab("b")
        val c = SelfClosingTab("c")
        listOf(a, b, c).forEach { tabs.open(it) }
        tabs.move(a, 2)
        tabs.moveTo(b, Side.RIGHT)
        assertEquals(1, a.closeRequested.attached)
        assertEquals(1, b.closeRequested.attached)

        a.closeRequested.set(true)
        b.closeRequested.set(true)

        assertEquals(listOf("c"), names())
        assertEquals(1, a.disposed)
        assertEquals(1, b.disposed)
    }

    @Test
    fun `tabs closed together are no longer watched`() {
        val a = SelfClosingTab("a")
        val b = SelfClosingTab("b")
        tabs.open(a)
        tabs.open(b)

        tabs.closeAll()

        assertEquals(0, a.closeRequested.attached)
        assertEquals(0, b.closeRequested.attached)
    }
}
