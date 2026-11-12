package dev.sluide.codereview.list

import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.modified
import dev.sluide.codereview.model.prepareFile
import dev.sluide.codereview.model.textDiff
import dev.sluide.fileviewers.DiffKind
import dev.sluide.fileviewers.LineChange
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Selecting lines, copying, the context menus and the keys of the diff list; events are fired at the nodes. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReviewDiffListLinesUiTest {
    private val document = ReviewDocument()
    private lateinit var view: ReviewDiffList
    private lateinit var stage: Stage

    @TempDir
    lateinit var root: Path

    private val opened = mutableListOf<Pair<Path, Int?>>()
    private val histories = mutableListOf<Path>()
    private val viewed = mutableListOf<Pair<String, Boolean>>()
    private val mac = System.getProperty("os.name").contains("Mac", ignoreCase = true)

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        view = ReviewDiffList(document.rows, document)
        view.onViewedChanged = { path, value -> viewed += path to value }
        stage.scene = Scene(view, 600.0, 400.0)
        stage.show()
    }

    /** [block] on the FX thread, where the list's state lives. */
    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T>(block).get()

    private fun load(
        vararg files: Pair<String, ReviewFileContent>,
        layout: ReviewLayout = ReviewLayout.SPLIT,
    ) = fx {
        view.lineActions = LineActions(root, { path, line -> opened += path to line }, { path -> histories.add(path) })
        document.layout.set(layout)
        document.setFiles(files.map { modified(it.first) })
        for ((path, content) in files) document.setContent(path, prepareFile(modified(path), content, null, false))
    }

    private val small = textDiff(10, 10, LineChange(2..4, 2..4))

    private fun settle() {
        fx {
            view.applyCss()
            view.layout()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun cell(index: Int): Node = assertNotNull(view.rowCell(index), "row $index is not shown")

    private fun splitRow(current: Int) =
        document.rows.indexOfFirst { it is ReviewRow.SplitLine && it.current == current }

    /** The column [part] of the line row [index]: split 0 and 1 the original side, 2 and 3 the current one. */
    private fun column(
        index: Int,
        part: Int,
    ): Node = (cell(index) as LineRowPane).childrenUnmodifiable[part]

    private fun press(
        node: Node,
        shift: Boolean = false,
    ) = fx {
        Event.fireEvent(
            node,
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                1,
                shift,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null,
            ),
        )
    }

    private fun key(
        code: KeyCode,
        shift: Boolean = false,
        alt: Boolean = false,
        shortcut: Boolean = false,
    ) = fx { fireKey(code, shift, alt, shortcut) }

    private fun fireKey(
        code: KeyCode,
        shift: Boolean = false,
        alt: Boolean = false,
        shortcut: Boolean = false,
    ) = Event.fireEvent(
        view,
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, alt, shortcut && mac),
    )

    private fun contextMenu(
        node: Node,
        keyboard: Boolean = false,
    ) = fx {
        Event.fireEvent(
            node,
            ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                5.0,
                5.0,
                stage.x + 5,
                stage.y + 5,
                keyboard,
                null,
            ),
        )
    }

    private fun shownMenu(): ContextMenu =
        fx { assertNotNull(Window.getWindows().filterIsInstance<ContextMenu>().firstOrNull { it.isShowing }) }

    private fun hideMenu() = fx { Window.getWindows().filterIsInstance<ContextMenu>().forEach { it.hide() } }

    private fun ContextMenu.item(text: String): MenuItem =
        assertNotNull(items.firstOrNull { it.text?.startsWith(text) == true }, "no $text in ${items.map { it.text }}")

    private fun clipboardText(): String? = fx { Clipboard.getSystemClipboard().string }

    private fun selected(index: Int): List<Int> =
        fx {
            (cell(index) as LineRowPane)
                .childrenUnmodifiable
                .withIndex()
                .filter { "review-selected" in it.value.styleClass }
                .map { it.index }
        }

    /** Row [index] is shown right below the sticky header (a file header at the very top). */
    private fun assertAtTop(index: Int) {
        settle()
        val isHeader = document.rows[index] is ReviewRow.FileHeader
        val sticky = if (isHeader) 0.0 else fx { view.lookup(".review-sticky").layoutBounds.height }
        val top = fx { cell(index).localToScene(0.0, 0.0).y - view.localToScene(0.0, 0.0).y }
        assertTrue(abs(top - sticky) < 2.0, "row $index is at $top, expected $sticky")
    }

    @Test
    fun `clicking numbers selects the lines, paints them and Shortcut+C copies their text`() {
        load("a.txt" to small)
        settle()
        val first = splitRow(2)
        val last = splitRow(3)

        press(column(first, 2))
        press(column(last, 2), shift = true)
        key(KeyCode.C, shortcut = true)

        assertEquals("c2\nc3", clipboardText())
        assertEquals(listOf(2, 3), selected(first))
        assertEquals(listOf(2, 3), selected(last))
        assertEquals(emptyList(), selected(splitRow(1)))
    }

    @Test
    fun `Escape clears the selection and is not consumed without one`() {
        load("a.txt" to small)
        settle()
        val row = splitRow(2)
        // fireEvent dispatches copies, so a consumed event is told by not reaching the scene.
        var reached = 0
        fx { view.scene.addEventHandler(KeyEvent.KEY_PRESSED) { reached++ } }

        key(KeyCode.ESCAPE)
        val withoutSelection = fx { reached }
        press(column(row, 2))
        key(KeyCode.ESCAPE)

        assertEquals(1, withoutSelection)
        assertEquals(1, fx { reached })
        assertEquals(emptyList(), selected(row))
    }

    @Test
    fun `Open in Editor of a selected line opens the file at its line and is disabled for a missing file`() {
        Files.writeString(root.resolve("a.txt"), "x")
        load("a.txt" to small, "gone.txt" to small)
        settle()
        val row = splitRow(3)

        press(column(row, 2))
        contextMenu(column(row, 3))
        val menu = shownMenu()
        assertEquals(listOf("Copy Lines", "Open in Editor", "Copy Path"), fx { menu.items.map { it.text } })
        fx { menu.item("Open in Editor").fire() }

        assertEquals(listOf<Pair<Path, Int?>>(root.resolve("a.txt") to 3), opened)

        val goneRow =
            document.rows.indexOfFirst { it is ReviewRow.SplitLine && it.path == "gone.txt" && it.current == 3 }
        hideMenu()
        contextMenu(column(goneRow, 2))
        val missing = fx { shownMenu().item("Open in Editor") }
        assertTrue(fx { missing.isDisable })
        assertEquals("Open in Editor (not in the working tree)", fx { missing.text })
    }

    @Test
    fun `Open in Editor is disabled for a directory`() {
        Files.createDirectory(root.resolve("a.txt"))
        load("a.txt" to small)
        settle()

        contextMenu(column(splitRow(3), 3))

        assertTrue(fx { shownMenu().item("Open in Editor").isDisable })
    }

    @Test
    fun `a right click on an unselected line selects it`() {
        load("a.txt" to small)
        settle()
        val row = splitRow(2)

        contextMenu(column(row, 3))

        assertEquals(listOf(2, 3), selected(row))
        hideMenu()
    }

    @Test
    fun `the keyboard context menu opens the menu of the selected lines, and nothing without a selection`() {
        load("a.txt" to small)
        settle()

        contextMenu(view, keyboard = true)
        assertEquals(emptyList(), fx { Window.getWindows().filterIsInstance<ContextMenu>().filter { it.isShowing } })

        press(column(splitRow(2), 2))
        contextMenu(view, keyboard = true)

        assertEquals("Copy Lines", fx { shownMenu().items.first().text })
        hideMenu()
    }

    @Test
    fun `the context menu of a file header shows history, copies the path and marks it viewed`() {
        load("dir/a.txt" to small)
        settle()

        contextMenu(cell(0))
        val menu = shownMenu()
        assertEquals(
            listOf<String?>("Open in Editor (not in the working tree)", "Show History", "Copy Path", null) +
                "Mark as Viewed",
            fx { menu.items.map { it.text } },
        )
        fx { menu.item("Show History").fire() }
        fx { menu.item("Copy Path").fire() }
        fx { menu.item("Mark as Viewed").fire() }

        assertEquals(listOf(root.resolve("dir/a.txt")), histories)
        assertEquals("dir/a.txt", clipboardText())
        assertEquals(listOf("dir/a.txt" to true), viewed)
    }

    @Test
    fun `the sticky header has the header menu too`() {
        load("dir/a.txt" to bigFile())
        settle()
        fx { view.reveal(splitRow(325)) }
        settle()
        val sticky = fx { view.lookup(".review-sticky") }
        assertTrue(fx { sticky.isVisible }, "the sticky header is not shown")

        contextMenu(fx { (sticky as javafx.scene.Parent).childrenUnmodifiable.first() })
        fx { shownMenu().item("Show History").fire() }

        assertEquals(listOf(root.resolve("dir/a.txt")), histories)
    }

    private fun bigFile() =
        textDiff(2000, 2000, *Array(50) { LineChange((5 + 40 * it)..(5 + 40 * it), (5 + 40 * it)..(5 + 40 * it)) })

    @Test
    fun `F7 and Shift+F7 reveal the next and previous change, Alt+Down and Alt+Up the next and previous file`() {
        val big = textDiff(300, 300, LineChange(20..21, 20..21), LineChange(150..150, 150..150))
        load("a.txt" to big, "b.txt" to big, "c.txt" to big)
        settle()
        val rows = document.rows
        val changes =
            rows.indices.filter { i ->
                (rows[i] as? ReviewRow.SplitLine)?.let { it.current == 20 || it.current == 150 } ?: false
            }
        val headers = rows.indices.filter { rows[it] is ReviewRow.FileHeader }

        key(KeyCode.F7)
        assertAtTop(changes[0])
        key(KeyCode.F7)
        assertAtTop(changes[1])
        key(KeyCode.F7, shift = true)
        assertAtTop(changes[0])

        key(KeyCode.DOWN, alt = true)
        assertAtTop(headers[1])
        key(KeyCode.UP, alt = true)
        assertAtTop(headers[0])
    }

    @Test
    fun `F7 goes on from the last jump while the selection is still shown, not back from the selection`() {
        // Everything is shown: C is too near the end to stand at the top, and the selection stays on screen.
        load("a.txt" to textDiff(18, 18, LineChange(2..2, 2..2), LineChange(8..8, 8..8), LineChange(14..14, 14..14)))
        settle()
        val rows = document.rows
        val changes = rows.indices.filter { (rows[it] as? ReviewRow.SplitLine)?.kind == DiffKind.MODIFIED }
        assertNotNull(view.rowCell(rows.lastIndex), "the whole file is shown")
        press(column(changes[0], 2))

        // Read in the same pulse: reveal answers with the row it was asked for before any layout.
        val first = fx { fireKey(KeyCode.F7).let { view.firstVisible().first } }
        val second = fx { fireKey(KeyCode.F7).let { view.firstVisible().first } }

        assertEquals(changes[1], first)
        assertEquals(changes[2], second)
    }

    @Test
    fun `a selected row is painted again when its cell is created after scrolling away and back`() {
        load("a.txt" to bigFile())
        settle()
        val row = splitRow(5)
        press(column(row, 2))

        fx { view.scrollToRow(300) }
        settle()
        assertEquals(null, fx { view.rowCell(row) })
        fx { view.scrollToRow(0) }
        settle()

        assertEquals(listOf(2, 3), selected(row))
    }

    @Test
    fun `the selection colour is laid over the colour of the change, not instead of it`() {
        // Two rows of each kind: one selected, the other for what the unselected colour is.
        load(
            "a.txt" to
                textDiff(14, 14, LineChange(2..3, 2..3), LineChange(6..7, 6 until 6), LineChange(10 until 10, 8..9)),
        )
        settle()
        val rows = document.rows

        fun kindRows(kind: DiffKind) = rows.indices.filter { (rows[it] as? ReviewRow.SplitLine)?.kind == kind }
        // The part to click and the columns to look at: the side with the line, its number and its text.
        val cases =
            listOf(
                Triple(DiffKind.REMOVED, 0, listOf(0, 1)),
                Triple(DiffKind.MODIFIED, 2, listOf(2, 3)),
                Triple(DiffKind.ADDED, 2, listOf(2, 3)),
            )

        for ((kind, number, parts) in cases) {
            val (picked, other) = kindRows(kind).let { it[0] to it[1] }
            press(column(picked, number))
            settle()
            for (part in parts) {
                val fills = fx { (column(picked, part) as Region).background.fills }
                val plain = fx { (column(other, part) as Region).background.fills }
                assertEquals(2, fills.size, "$kind column $part")
                assertEquals(plain.first().fill, fills.first().fill, "$kind column $part keeps the change colour")
                assertTrue((fills.last().fill as Color).opacity < 1.0, "$kind column $part has the tint on top")
            }
        }
    }
}
