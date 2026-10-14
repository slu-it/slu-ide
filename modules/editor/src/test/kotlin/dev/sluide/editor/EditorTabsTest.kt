package dev.sluide.editor

import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.TextDiff
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ListChangeListener
import javafx.scene.Group
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private class EditedView : EditableFileView {
        override val node = Group()
        override val dirty = SimpleBooleanProperty(false)

        override fun reload() {}

        override fun save() = dirty.set(false)
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

    private class CountingDiff : DiffFileView {
        var disposed = 0

        override val node = Group()

        override fun reload() {}

        override fun show(diff: TextDiff) {}

        override fun dispose() {
            disposed++
        }
    }

    @Test
    fun `a diff over the editor is disposed when hidden or replaced, the editor when the tab closes`() {
        val editor = CountingView()
        val first = CountingDiff()
        val second = CountingDiff()
        val file = open("a.txt", editor)

        file.diffView = first
        assertSame(first, file.view)
        assertSame(first.node, file.content.children.last())
        assertFalse(
            file.content.children
                .first()
                .isVisible,
            "the editor is hidden",
        )

        file.diffView = second
        file.diffView = second
        assertEquals(listOf(1, 0), listOf(first, second).map { it.disposed })

        file.diffView = null
        assertEquals(1, second.disposed)
        assertSame(editor, file.view)
        assertTrue(
            file.content.children
                .single()
                .isVisible,
        )
        assertEquals(0, editor.disposed)

        val third = CountingDiff()
        file.diffView = third
        tabs.close(file)
        assertEquals(listOf(1, 1), listOf(editor.disposed, third.disposed))
    }

    @Test
    fun `a file has a diff while it knows one or shows one`() {
        val file = open("a.txt", CountingView())
        assertFalse(file.hasDiff.get())

        file.diff.set(TextDiff("", emptyList()))
        assertTrue(file.hasDiff.get())

        file.diffView = CountingDiff()
        file.diff.set(null)
        assertTrue(file.hasDiff.get())
    }

    @Test
    fun `closing several file tabs disposes each view once`() {
        val views = List(3) { CountingView() }
        views.forEachIndexed { i, view -> open("$i.txt", view) }

        tabs.closeAll()

        assertEquals(listOf(1, 1, 1), views.map { it.disposed })
    }

    @Test
    fun `the title of a file with unsaved changes starts with a dot`() {
        val view = EditedView()
        val file = open("a.txt", view)

        view.dirty.set(true)
        assertEquals(listOf("● a.txt"), names())
        assertTrue(file.isDirty)

        view.save()
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun `the dot follows the editor while its diff is shown`() {
        val edited = EditedView()
        val file = open("a.txt", edited)
        val titles = mutableListOf<String>()
        file.title.subscribe { title -> titles += title }

        edited.dirty.set(true)
        file.diffView = CountingDiff()
        edited.save()

        assertEquals(listOf("a.txt", "● a.txt", "a.txt"), titles)
        assertFalse(file.isDirty)
    }
}
