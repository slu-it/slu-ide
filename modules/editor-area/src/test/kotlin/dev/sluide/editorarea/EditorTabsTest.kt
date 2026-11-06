package dev.sluide.editorarea

import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.TextDiff
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ListChangeListener
import javafx.scene.Group
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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
    fun `opening without selecting keeps the selection, unless nothing is selected`() {
        val a = tabs.open(Path.of("/project", "a.txt"), CountingView(), select = false)
        assertSame(a, tabs.selected.get())

        tabs.open(Path.of("/project", "b.txt"), CountingView(), select = false)

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertSame(a, tabs.selected.get())
    }

    @Test
    fun `finds open files by path and refuses to open them twice`() {
        val a = open("a.txt")

        assertSame(a, tabs.find(Path.of("/project", "a.txt")))
        assertNull(tabs.find(Path.of("/project", "other.txt")))
        assertFailsWith<IllegalArgumentException> { open("a.txt") }
    }

    @Test
    fun `a file tab's title and tooltip follow its path`() {
        val view = EditedView()
        val a = open("a.txt", view)

        a.path = Path.of("/project", "b.txt")
        assertEquals(listOf("b.txt"), names())
        assertEquals("/project/b.txt", a.tooltip)

        view.dirty.set(true)
        a.path = Path.of("/project", "c.txt")
        assertEquals(listOf("● c.txt"), names())
        assertSame(a, tabs.find(Path.of("/project", "c.txt")))
    }

    @Test
    fun `replacing a tab puts the new one in its place, selected if it was, and disposes the old one`() {
        val view = CountingView()
        val a = open("a.txt", view)
        val b = open("b.txt")
        tabs.select(a)
        val changes = mutableListOf<String>()
        tabs.tabs.addListener(
            ListChangeListener { change ->
                while (change.next()) {
                    changes +=
                        "-" + change.removed.map { it.title.value } + " +" + change.addedSubList.map { it.title.value }
                }
            },
        )
        val replacement = OpenFile(Path.of("/project", "a.py"), CountingView())

        tabs.replace(a, replacement)

        assertEquals(listOf<EditorTab>(replacement, b), tabs.tabs)
        assertSame(replacement, tabs.selected.get())
        assertEquals(1, view.disposed)
        assertEquals(listOf("-[a.txt] +[a.py]"), changes)

        tabs.replace(b, OpenFile(Path.of("/project", "b.py"), CountingView()))
        assertSame(replacement, tabs.selected.get(), "the selection stays when another tab is replaced")
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
    fun `files at or below a path are found, a sibling that shares the name's start is not below it`() {
        val inner = tabs.open(Path.of("/project/src/x/inner.txt"), EditedView())
        val top = tabs.open(Path.of("/project/src/top.txt"), EditedView())
        val sibling = tabs.open(Path.of("/project/src2/other.txt"), EditedView())
        val clean = tabs.open(Path.of("/project/src/clean.txt"), CountingView())
        tabs.open(DisposableTab("not a file"))

        assertEquals(listOf(inner, top, clean), tabs.filesUnder(Path.of("/project/src/x/../")))
        assertEquals(listOf(inner, top, clean), tabs.filesUnder(Path.of("/project/src")))
        assertEquals(listOf(top), tabs.filesUnder(Path.of("/project/src/top.txt")))
        assertEquals(listOf(sibling), tabs.filesUnder(Path.of("/project/src2")))
        assertEquals(emptyList(), tabs.filesUnder(Path.of("/project/src/none")))
        assertEquals(4, tabs.filesUnder(Path.of("/project")).size, "tabs that are no files are skipped")
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

    /**
     * Opens tabs named [left] and [right] and moves the [right] ones to the right side. Each side then has its last tab
     * selected, and the right side is current.
     */
    private fun sides(
        left: List<String>,
        right: List<String>,
    ): Map<String, DisposableTab> {
        val opened = (left + right).associateWith { DisposableTab(it).also(tabs::open) }
        right.forEach { tabs.moveTo(opened.getValue(it), Side.RIGHT) }
        return opened
    }

    private fun names(side: Side) = tabs.tabsOf(side).map { it.title.value }

    private fun selectedName(side: Side) =
        tabs
            .selectedOf(side)
            .get()
            ?.title
            ?.value

    /** Records the changes of [EditorTabs.tabs] [action] makes: "permutation", "removed n" or "added n". */
    private fun listChanges(action: () -> Unit): List<String> {
        val changes = mutableListOf<String>()
        val listener =
            ListChangeListener<EditorTab> { change ->
                while (change.next()) {
                    changes +=
                        when {
                            change.wasPermutated() -> "permutation"
                            change.wasRemoved() -> "removed ${change.removedSize}"
                            else -> "added ${change.addedSize}"
                        }
                }
            }
        tabs.tabs.addListener(listener)
        action()
        tabs.tabs.removeListener(listener)
        return changes
    }

    @Test
    fun `new tabs open on the left side, a selected one makes it current, one in the background changes nothing`() {
        sides(listOf("a"), listOf("r"))
        assertEquals(Side.RIGHT, tabs.current.get())

        val back = DisposableTab("back").also { tabs.open(it, select = false) }
        assertEquals(listOf("a", "back"), names(Side.LEFT))
        assertEquals(listOf("a", "back", "r"), names(), "the left side's tabs come first")
        assertEquals(Side.RIGHT, tabs.current.get())
        assertEquals(
            "r",
            tabs.selected
                .get()
                ?.title
                ?.value,
        )
        assertEquals("a", selectedName(Side.LEFT))

        val front = DisposableTab("front").also(tabs::open)
        assertEquals(listOf("a", "back", "front", "r"), names())
        assertEquals(Side.LEFT, tabs.current.get())
        assertSame(front, tabs.selected.get())
        assertEquals("r", selectedName(Side.RIGHT), "the right side keeps its selection")
        assertSame(back, tabs.tabsOf(Side.LEFT)[1])
    }

    @Test
    fun `selecting a tab makes its side current, the right side only while it has tabs`() {
        val opened = sides(listOf("a", "b"), listOf("c", "d"))

        tabs.select(opened.getValue("a"))
        assertEquals(Side.LEFT, tabs.current.get())
        assertEquals(
            "a",
            tabs.selected
                .get()
                ?.title
                ?.value,
        )
        assertEquals("d", selectedName(Side.RIGHT))

        tabs.select(opened.getValue("c"))
        assertEquals(Side.RIGHT, tabs.current.get())
        assertEquals(
            "c",
            tabs.selected
                .get()
                ?.title
                ?.value,
        )
        assertEquals("a", selectedName(Side.LEFT))

        tabs.makeCurrent(Side.LEFT)
        assertEquals(
            "a",
            tabs.selected
                .get()
                ?.title
                ?.value,
        )
        tabs.makeCurrent(Side.RIGHT)
        assertEquals(
            "c",
            tabs.selected
                .get()
                ?.title
                ?.value,
        )

        tabs.closeAll(Side.RIGHT)
        tabs.makeCurrent(Side.RIGHT)
        assertEquals(Side.LEFT, tabs.current.get(), "no right side to make current")
    }

    @ParameterizedTest(name = "moving {0} with {1} selected leaves {2} selected on the left")
    @CsvSource("b, b, c", "c, c, b", "a, b, b")
    fun `moving a tab to the other side is a permutation that selects it there`(
        moved: String,
        selectedBefore: String,
        selectedAfter: String,
    ) {
        val opened = sides(listOf("a", "b", "c"), listOf("r"))
        tabs.select(opened.getValue(selectedBefore))
        val tab = opened.getValue(moved)

        val changes = listChanges { tabs.moveTo(tab, Side.RIGHT) }

        assertEquals(listOf("permutation"), changes, "not a close and an open")
        assertEquals(listOf("r", moved), names(Side.RIGHT))
        assertEquals(names(Side.LEFT) + names(Side.RIGHT), names())
        assertSame(tab, tabs.selected.get())
        assertEquals(Side.RIGHT, tabs.current.get())
        assertEquals(selectedAfter, selectedName(Side.LEFT))
        assertEquals(0, tab.disposed)
    }

    @Test
    fun `a tab moves to the other side at a position, never to its own side`() {
        val opened = sides(listOf("a", "b"), listOf("x", "y"))

        tabs.moveTo(opened.getValue("a"), Side.RIGHT, 1)
        assertEquals(listOf("x", "a", "y"), names(Side.RIGHT))
        tabs.moveTo(opened.getValue("y"), Side.LEFT, 0)
        assertEquals(listOf("y", "b", "x", "a"), names())
        assertEquals(Side.LEFT, tabs.current.get())

        assertFailsWith<IllegalArgumentException> { tabs.moveTo(opened.getValue("b"), Side.LEFT) }
        assertFailsWith<IllegalArgumentException> { tabs.moveTo(opened.getValue("b"), Side.RIGHT, 3) }
        assertFailsWith<IllegalArgumentException> { tabs.moveTo(DisposableTab("other"), Side.RIGHT) }
    }

    @Test
    fun `the only tab stays on the left side when it is moved to the right`() {
        val only = DisposableTab("only").also(tabs::open)
        val notified = mutableListOf<String>()
        tabs.tabsOf(Side.LEFT).addListener(ListChangeListener { notified += "left" })
        tabs.split.addListener { _ -> notified += "split" }

        val changes = listChanges { tabs.moveTo(only, Side.RIGHT) }

        assertEquals(emptyList(), changes)
        assertEquals(emptyList<String>(), notified, "not opened and collapsed again")
        assertEquals(Side.LEFT, tabs.sideOf(only))
        assertSame(only, tabs.selected.get())
    }

    @Test
    fun `a listener that makes a side current while a move is published does not undo the move's current side`() {
        // The right side is current with r selected; b, selected on the left, moves there and a takes its place.
        val b = sides(listOf("a", "b"), listOf("r")).getValue("b")
        // As a side's pane does when its selection changes and it takes the focus.
        tabs.selectedOf(Side.LEFT).addListener { _ -> tabs.makeCurrent(Side.LEFT) }
        val values = mutableListOf<String?>()
        tabs.selected.addListener { _, _, tab -> values += tab?.title?.value }

        tabs.moveTo(b, Side.RIGHT)

        assertEquals(Side.RIGHT, tabs.current.get())
        assertSame(b, tabs.selected.get())
        assertEquals(listOf<String?>("b"), values)
    }

    @ParameterizedTest
    @ValueSource(strings = ["close", "moveTo", "closeAll"])
    fun `the right side ends with its last tab, the left side becomes current`(how: String) {
        val opened = sides(listOf("a", "b"), listOf("r"))
        val r = opened.getValue("r")

        when (how) {
            "close" -> tabs.close(r)
            "moveTo" -> tabs.moveTo(r, Side.LEFT)
            else -> tabs.closeAll(Side.RIGHT)
        }

        assertFalse(tabs.split.get())
        assertEquals(Side.LEFT, tabs.current.get())
        assertNull(tabs.selectedOf(Side.RIGHT).get())
        val expected = if (how == "moveTo") "r" else "b"
        assertEquals(
            expected,
            tabs.selected
                .get()
                ?.title
                ?.value,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["close", "moveTo", "closeAll"])
    fun `when the left side loses its last tab, the right side's tabs become the left side's`(how: String) {
        val opened = sides(listOf("a"), listOf("c", "d", "e"))
        tabs.select(opened.getValue("d"))
        tabs.makeCurrent(Side.LEFT)
        val a = opened.getValue("a")

        val changes =
            listChanges {
                when (how) {
                    "close" -> tabs.close(a)
                    "moveTo" -> tabs.moveTo(a, Side.RIGHT, 0)
                    else -> tabs.closeAll(Side.LEFT)
                }
            }

        val right = if (how == "moveTo") listOf("a", "c", "d", "e") else listOf("c", "d", "e")
        assertEquals(right, names(Side.LEFT), "in the same order")
        assertEquals(right, names(), "the tab list itself does not change by the collapse")
        assertEquals(if (how == "moveTo") emptyList() else listOf("removed 1"), changes)
        assertEquals(emptyList(), names(Side.RIGHT))
        assertFalse(tabs.split.get())
        assertEquals(Side.LEFT, tabs.current.get())
        assertEquals(
            if (how == "moveTo") "a" else "d",
            tabs.selected
                .get()
                ?.title
                ?.value,
        )
    }

    @ParameterizedTest(name = "{0} of d")
    @CsvSource(
        "closeOthers, d, d, RIGHT",
        "closeLeftOf, d e, d, RIGHT",
        "closeRightOf, c d, c, RIGHT",
        "closeAll, '', , LEFT",
    )
    fun `the close actions of a tab act on its side only`(
        action: String,
        rightAfter: String,
        selectedRight: String?,
        current: String,
    ) {
        val opened = sides(listOf("a", "b"), listOf("c", "d", "e"))
        tabs.select(opened.getValue("c"))
        tabs.select(opened.getValue("a"))
        val d = opened.getValue("d")

        when (action) {
            "closeOthers" -> tabs.closeOthers(d)
            "closeLeftOf" -> tabs.closeLeftOf(d)
            "closeRightOf" -> tabs.closeRightOf(d)
            else -> tabs.closeAll(Side.RIGHT)
        }

        assertEquals(rightAfter.split(" ").filter { it.isNotEmpty() }, names(Side.RIGHT))
        assertEquals(listOf("a", "b"), names(Side.LEFT))
        assertEquals("a", selectedName(Side.LEFT))
        assertEquals(selectedRight, selectedName(Side.RIGHT))
        assertEquals(Side.valueOf(current), tabs.current.get())
    }

    @Test
    fun `a tab moves within the right side as a permutation, keeping the selections`() {
        val opened = sides(listOf("a"), listOf("c", "d", "e"))

        val changes = listChanges { tabs.move(opened.getValue("c"), 2) }

        assertEquals(listOf("permutation"), changes)
        assertEquals(listOf("d", "e", "c"), names(Side.RIGHT))
        assertEquals(listOf("a", "d", "e", "c"), names())
        assertEquals("e", selectedName(Side.RIGHT))
        assertFailsWith<IllegalArgumentException> { tabs.move(opened.getValue("c"), 3) }
    }

    @Test
    fun `a tab on the right side is replaced in its place there`() {
        val opened = sides(listOf("a"), listOf("c", "d"))
        val replacement = DisposableTab("d2")

        tabs.replace(opened.getValue("d"), replacement)

        assertEquals(listOf("c", "d2"), names(Side.RIGHT))
        assertEquals(listOf("a", "c", "d2"), names())
        assertSame(replacement, tabs.selectedOf(Side.RIGHT).get())
        assertSame(replacement, tabs.selected.get())
        assertEquals(1, opened.getValue("d").disposed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["moveTo", "closeLastRight", "closeOnlyLeft", "closeOthers", "closeAllLeft"])
    fun `the selected tab changes once per operation, to its final value`(operation: String) {
        val opened = sides(listOf("a", "b"), listOf("c", "d"))
        tabs.select(opened.getValue("a"))
        val values = mutableListOf<String?>()
        tabs.selected.addListener { _, _, tab -> values += tab?.title?.value }

        val expected: String =
            when (operation) {
                "moveTo" -> {
                    "b".also { tabs.moveTo(opened.getValue("b"), Side.RIGHT) }
                }

                "closeLastRight" -> {
                    tabs.closeLeftOf(opened.getValue("d"))
                    tabs.select(opened.getValue("d"))
                    values.clear()
                    "a".also { tabs.close(opened.getValue("d")) }
                }

                "closeOnlyLeft" -> {
                    tabs.close(opened.getValue("b"))
                    values.clear()
                    "d".also { tabs.close(opened.getValue("a")) }
                }

                "closeOthers" -> {
                    "c".also { tabs.closeOthers(opened.getValue("c")) }
                }

                else -> {
                    "d".also { tabs.closeAll(Side.LEFT) }
                }
            }

        assertEquals(listOf<String?>(expected), values)
    }
}
