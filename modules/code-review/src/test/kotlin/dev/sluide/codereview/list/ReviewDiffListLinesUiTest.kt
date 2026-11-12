package dev.sluide.codereview.list

import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.modified
import dev.sluide.codereview.model.prepareFile
import dev.sluide.codereview.model.textDiff
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
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
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

    private fun load(
        layout: ReviewLayout = ReviewLayout.SPLIT,
        vararg files: Pair<String, ReviewFileContent>,
    ) {
        view.lineActions =
            LineActions(root, { path, line -> opened += path to line }, { path -> histories.add(path) })
        document.layout.set(layout)
        document.setFiles(files.map { modified(it.first) })
        for ((path, content) in files) document.setContent(path, prepareFile(modified(path), content, null, false))
    }

    private val small = textDiff(10, 10, LineChange(2..4, 2..4))

    private fun settle(robot: FxRobot) =
        robot.interact {
            view.applyCss()
            view.layout()
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
    ) = Event.fireEvent(
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

    private fun key(
        code: KeyCode,
        shift: Boolean = false,
        alt: Boolean = false,
        shortcut: Boolean = false,
    ): KeyEvent {
        val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, alt, shortcut && mac)
        Event.fireEvent(view, event)
        return event
    }

    private fun contextMenu(node: Node) {
        Event.fireEvent(
            node,
            ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5.0, 5.0, stage.x + 5, stage.y + 5, false, null),
        )
    }

    private fun shownMenu(): ContextMenu =
        assertNotNull(Window.getWindows().filterIsInstance<ContextMenu>().firstOrNull { it.isShowing })

    private fun ContextMenu.item(text: String): MenuItem =
        assertNotNull(
            items.firstOrNull { it.text?.startsWith(text) == true },
            "no item $text in ${items.map { it.text }}",
        )

    private fun clipboardText(robot: FxRobot): String? {
        var text: String? = null
        robot.interact { text = Clipboard.getSystemClipboard().string }
        return text
    }

    /** Row [index] is shown right below the sticky header (a file header at the very top). */
    private fun assertAtTop(
        robot: FxRobot,
        index: Int,
    ) {
        settle(robot)
        val sticky =
            if (document.rows[index] is ReviewRow.FileHeader) {
                0.0
            } else {
                view
                    .lookup(
                        ".review-sticky",
                    ).layoutBounds.height
            }
        val top = cell(index).localToScene(0.0, 0.0).y - view.localToScene(0.0, 0.0).y
        assertTrue(kotlin.math.abs(top - sticky) < 2.0, "row $index is at $top, expected $sticky")
    }

    private fun selected(index: Int): List<Int> =
        (
            cell(
                index,
            ) as LineRowPane
        ).childrenUnmodifiable
            .withIndex()
            .filter { "review-selected" in it.value.styleClass }
            .map { it.index }

    @Test
    fun `clicking a number and shift clicking another selects the lines, paints them and Shortcut+C copies their text`(
        robot: FxRobot,
    ) {
        robot.interact { load(ReviewLayout.SPLIT, "a.txt" to small) }
        settle(robot)
        val first = splitRow(2)
        val last = splitRow(3)

        robot.interact {
            press(column(first, 2))
            press(column(last, 2), shift = true)
        }
        val copied =
            robot.run {
                interact { key(KeyCode.C, shortcut = true) }
                clipboardText(robot)
            }

        assertEquals("c2\nc3", copied)
        assertEquals(listOf(2, 3), selected(first))
        assertEquals(listOf(2, 3), selected(last))
        assertEquals(emptyList(), selected(splitRow(1)))
    }

    @Test
    fun `Escape clears the selection and is not consumed without one`(robot: FxRobot) {
        robot.interact { load(ReviewLayout.SPLIT, "a.txt" to small) }
        settle(robot)
        val row = splitRow(2)
        // fireEvent dispatches copies, so a consumed event is told by not reaching the scene.
        var reachedScene = 0
        var reachedFirst = 0
        var reachedWith = 0
        view.scene.addEventHandler(KeyEvent.KEY_PRESSED) { reachedScene++ }

        robot.interact {
            key(KeyCode.ESCAPE)
            reachedFirst = reachedScene
            press(column(row, 2))
            key(KeyCode.ESCAPE)
            reachedWith = reachedScene - reachedFirst
        }

        assertEquals(1, reachedFirst)
        assertEquals(0, reachedWith)
        assertEquals(emptyList(), selected(row))
    }

    @Test
    fun `Open in Editor of a selected line opens the file at its line and is disabled for a missing file`(
        robot: FxRobot,
    ) {
        Files.writeString(root.resolve("a.txt"), "x")
        robot.interact { load(ReviewLayout.SPLIT, "a.txt" to small, "gone.txt" to small) }
        settle(robot)
        val row = splitRow(3)

        robot.interact {
            press(column(row, 2))
            contextMenu(column(row, 3))
        }
        val menu = shownMenu()
        assertEquals(listOf("Copy Lines", "Open in Editor", "Copy Path"), menu.items.map { it.text })
        robot.interact { menu.item("Open in Editor").fire() }

        assertEquals(listOf<Pair<Path, Int?>>(root.resolve("a.txt") to 3), opened)

        val goneRow =
            document.rows.indexOfFirst {
                it is ReviewRow.SplitLine && it.path == "gone.txt" && it.current == 3
            }
        robot.interact {
            menu.hide()
            contextMenu(column(goneRow, 2))
        }
        val missing = shownMenu().item("Open in Editor")
        assertTrue(missing.isDisable)
        assertEquals("Open in Editor (not in the working tree)", missing.text)
    }

    @Test
    fun `a right click on an unselected line selects it`(robot: FxRobot) {
        robot.interact { load(ReviewLayout.SPLIT, "a.txt" to small) }
        settle(robot)
        val row = splitRow(2)

        robot.interact { contextMenu(column(row, 3)) }

        assertEquals(listOf(2, 3), selected(row))
        robot.interact { shownMenu().hide() }
    }

    @Test
    fun `the context menu of a file header shows history, copies the path and marks it viewed`(robot: FxRobot) {
        robot.interact { load(ReviewLayout.SPLIT, "dir/a.txt" to small) }
        settle(robot)

        robot.interact { contextMenu(cell(0)) }
        val menu = shownMenu()
        assertEquals(
            listOf<String?>(
                "Open in Editor (not in the working tree)",
                "Show History",
                "Copy Path",
                null,
                "Mark as Viewed",
            ),
            menu.items.map { it.text },
        )
        robot.interact { menu.item("Show History").fire() }
        robot.interact { menu.item("Copy Path").fire() }
        robot.interact { menu.item("Mark as Viewed").fire() }

        assertEquals(listOf(root.resolve("dir/a.txt")), histories)
        assertEquals("dir/a.txt", clipboardText(robot))
        assertEquals(listOf("dir/a.txt" to true), viewed)
    }

    @Test
    fun `F7 and Shift+F7 reveal the next and previous change, Alt+Down and Alt+Up the next and previous file`(
        robot: FxRobot,
    ) {
        val big = textDiff(300, 300, LineChange(20..21, 20..21), LineChange(150..150, 150..150))
        robot.interact { load(ReviewLayout.SPLIT, "a.txt" to big, "b.txt" to big, "c.txt" to big) }
        settle(robot)
        val rows = document.rows
        val changes =
            rows.indices.filter { i ->
                (rows[i] as? ReviewRow.SplitLine)?.let {
                    it.current == 20 ||
                        it.current == 150
                }
                    ?: false
            }
        val headers = rows.indices.filter { rows[it] is ReviewRow.FileHeader }

        robot.interact { key(KeyCode.F7) }
        assertAtTop(robot, changes[0])
        robot.interact { key(KeyCode.F7) }
        assertAtTop(robot, changes[1])
        robot.interact { key(KeyCode.F7, shift = true) }
        assertAtTop(robot, changes[0])

        robot.interact { key(KeyCode.DOWN, alt = true) }
        assertAtTop(robot, headers[1])
        robot.interact { key(KeyCode.UP, alt = true) }
        assertAtTop(robot, headers[0])
    }
}
