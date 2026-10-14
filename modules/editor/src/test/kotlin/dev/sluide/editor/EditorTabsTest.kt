package dev.sluide.editor

import dev.sluide.fileviewers.FileView
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ListChangeListener
import javafx.scene.Group
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorTabsTest {
    private val tabs = EditorTabs()

    private class CountingView : FileView {
        var disposed = 0

        override val node = Group()

        override fun reload() {}

        override fun dispose() {
            disposed++
        }
    }

    private fun open(
        name: String,
        view: FileView = CountingView(),
    ) = tabs.open(Path.of("/project", name), view)

    private class DisposableTab(
        name: String,
    ) : EditorTab {
        var disposed = 0

        override val title = SimpleStringProperty(name)
        override val tooltip = name
        override val content = Group()

        override fun dispose() {
            disposed++
        }
    }

    private fun names() = tabs.tabs.map { it.title.value }

    @Test
    fun `opening appends a tab and selects it`() {
        open("a.txt")
        val b = open("b.txt")

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertSame(b, tabs.selected.get())
    }

    @Test
    fun `finds open files by path and refuses to open them twice`() {
        val a = open("a.txt")

        assertSame(a, tabs.find(Path.of("/project", "a.txt")))
        assertNull(tabs.find(Path.of("/project", "other.txt")))
        assertFailsWith<IllegalArgumentException> { open("a.txt") }
    }

    @Test
    fun `closing the selected tab selects its right neighbour`() {
        val a = open("a.txt")
        val b = open("b.txt")
        val c = open("c.txt")
        tabs.select(b)

        tabs.close(b)

        assertEquals(listOf("a.txt", "c.txt"), names())
        assertSame(c, tabs.selected.get())
        assertSame(a, tabs.tabs.first())
    }

    @Test
    fun `closing the selected last tab selects its left neighbour, closing the only tab selects nothing`() {
        val a = open("a.txt")
        val b = open("b.txt")

        tabs.close(b)
        assertSame(a, tabs.selected.get())

        tabs.close(a)
        assertNull(tabs.selected.get())
        assertEquals(emptyList(), names())
    }

    @Test
    fun `closing another tab keeps the selection`() {
        val a = open("a.txt")
        val b = open("b.txt")

        tabs.close(a)

        assertSame(b, tabs.selected.get())
    }

    @Test
    fun `closing all tabs clears the selection`() {
        open("a.txt")
        open("b.txt")

        tabs.closeAll()

        assertEquals(emptyList(), names())
        assertNull(tabs.selected.get())
    }

    @Test
    fun `closing others keeps and selects the given tab`() {
        open("a.txt")
        val b = open("b.txt")
        open("c.txt")

        tabs.closeOthers(b)

        assertEquals(listOf("b.txt"), names())
        assertSame(b, tabs.selected.get())
    }

    @Test
    fun `closing tabs to the right keeps a selection left of them`() {
        val a = open("a.txt")
        val b = open("b.txt")
        open("c.txt")
        open("d.txt")
        tabs.select(a)

        tabs.closeRightOf(b)

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertSame(a, tabs.selected.get())
    }

    @Test
    fun `closing tabs to the left selects the given tab if the selected one was closed`() {
        val a = open("a.txt")
        open("b.txt")
        val c = open("c.txt")
        open("d.txt")
        tabs.select(a)

        tabs.closeLeftOf(c)

        assertEquals(listOf("c.txt", "d.txt"), names())
        assertSame(c, tabs.selected.get())
    }

    @Test
    fun `nothing to close left of the first or right of the last tab`() {
        val a = open("a.txt")
        val b = open("b.txt")

        tabs.closeLeftOf(a)
        tabs.closeRightOf(b)

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertSame(b, tabs.selected.get())
    }

    @Test
    fun `other tabs open between files and are not found by path`() {
        open("a.txt")
        val terminal = DisposableTab("Terminal 1")
        tabs.open(terminal)
        val b = open("b.txt")

        assertEquals(listOf("a.txt", "Terminal 1", "b.txt"), names())
        assertEquals(listOf("a.txt", "b.txt"), tabs.files.map { it.name })
        assertSame(b, tabs.selected.get())
        assertFailsWith<IllegalArgumentException> { tabs.open(terminal) }
    }

    @Test
    fun `moving a tab reorders the tabs and keeps the selection`() {
        val a = open("a.txt")
        open("b.txt")
        val c = open("c.txt")
        tabs.select(a)

        tabs.move(a, 2)
        assertEquals(listOf("b.txt", "c.txt", "a.txt"), names())

        tabs.move(c, 0)
        assertEquals(listOf("c.txt", "b.txt", "a.txt"), names())
        assertSame(a, tabs.selected.get())
    }

    @Test
    fun `moving a tab is a permutation, nothing is closed or disposed`() {
        val one = DisposableTab("one").also(tabs::open)
        val two = DisposableTab("two").also(tabs::open)
        val changes = mutableListOf<String>()
        tabs.tabs.addListener(
            ListChangeListener { change ->
                while (change.next()) {
                    changes += if (change.wasPermutated()) "permutation" else "removed ${change.removedSize}"
                }
            },
        )

        tabs.move(one, 1)
        tabs.move(one, 1)

        assertEquals(listOf("permutation"), changes, "moving to the current position changes nothing")
        assertEquals(listOf(0, 0), listOf(one, two).map { it.disposed })
    }

    @Test
    fun `moving rejects tabs that are not open and positions out of range`() {
        val a = open("a.txt")

        assertFailsWith<IllegalArgumentException> { tabs.move(DisposableTab("other"), 0) }
        assertFailsWith<IllegalArgumentException> { tabs.move(a, 1) }
        assertFailsWith<IllegalArgumentException> { tabs.move(a, -1) }
    }

    @Test
    fun `closed tabs are disposed once, however they are closed`() {
        val one = DisposableTab("one").also(tabs::open)
        val two = DisposableTab("two").also(tabs::open)
        val three = DisposableTab("three").also(tabs::open)
        val four = DisposableTab("four").also(tabs::open)

        tabs.close(one)
        tabs.close(one)
        tabs.closeRightOf(three)
        tabs.closeOthers(three)

        assertEquals(listOf(1, 1, 0, 1), listOf(one, two, three, four).map { it.disposed })
        tabs.closeAll()
        assertTrue(listOf(one, two, three, four).all { it.disposed == 1 })
    }

    @Test
    fun `file views are disposed when replaced and when their tab closes`() {
        val first = CountingView()
        val second = CountingView()
        val file = open("a.txt", first)

        file.view = second
        assertEquals(listOf(1, 0), listOf(first, second).map { it.disposed })
        assertSame(second.node, file.content.children.single())

        file.view = second
        assertEquals(0, second.disposed, "setting the same view again keeps it")

        tabs.close(file)
        assertEquals(listOf(1, 1), listOf(first, second).map { it.disposed })
    }

    @Test
    fun `closing several file tabs disposes each view once`() {
        val views = List(3) { CountingView() }
        views.forEachIndexed { i, view -> open("$i.txt", view) }

        tabs.closeAll()

        assertEquals(listOf(1, 1, 1), views.map { it.disposed })
    }
}
