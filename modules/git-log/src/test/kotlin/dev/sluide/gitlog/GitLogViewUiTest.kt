package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.git.GitLogPage
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TableRow
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shows the real git log view with a fake loader. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewUiTest {
    private val repo = Path.of("/repo")

    /** The commits of the repository, newest first; `null` makes loading fail. */
    @Volatile
    private var history: List<GitCommit>? = commits(1200)

    /** The same for any other folder. */
    @Volatile
    private var otherHistory: List<GitCommit>? = null

    /** Loads wait for this while it is not `null`. */
    @Volatile
    private var gate: CountDownLatch? = null

    /** The (skip, limit) of every load. */
    private val loads = CopyOnWriteArrayList<Pair<Int, Int>>()

    private lateinit var view: GitLogView

    @Start
    fun start(stage: Stage) {
        view =
            GitLogView(
                { root, skip, limit ->
                    gate?.await(10, TimeUnit.SECONDS)
                    loads += skip to limit
                    (if (root == repo) history else otherHistory)?.let { all ->
                        val page = all.drop(skip).take(limit)
                        GitLogPage(page, complete = skip + page.size >= all.size)
                    }
                },
                Platform::runLater,
            )
        stage.scene = Scene(view, 800.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        gate?.countDown()
        view.close()
    }

    private fun commits(
        count: Int,
        prefix: String = "h",
    ) = (1..count).map {
        GitCommit(
            "$prefix$it",
            emptyList(),
            "Author $it",
            "a$it@example.com",
            Instant.ofEpochSecond(it * 60L),
            "Subject $it",
        )
    }

    private fun openAndExpand(robot: FxRobot) {
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        eventually { assertTrue(view.table.items.isNotEmpty()) }
    }

    /** Waits until [assertion] holds on the JavaFX thread. */
    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = WaitForAsyncUtils.asyncFx<Throwable?> { runCatching(assertion).exceptionOrNull() }.get()
            if (failure == null) return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(20)
        }
    }

    private fun click(
        robot: FxRobot,
        node: Node,
    ) {
        robot.interact {
            for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                Event.fireEvent(
                    node,
                    MouseEvent(
                        type,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        MouseButton.PRIMARY,
                        1,
                        false,
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
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun shownSubjects(robot: FxRobot): List<String> =
        robot
            .lookup(".git-log-table .table-row-cell")
            .queryAllAs(TableRow::class.java)
            .filter { !it.isEmpty && it.item != null }
            .sortedBy { it.index }
            .map { (it.item as GitCommit).subject }

    private fun placeholder(robot: FxRobot) =
        robot.lookup(".git-log-table .placeholder .label").queryAs(Label::class.java).text

    private fun clipboard() = WaitForAsyncUtils.asyncFx<String> { Clipboard.getSystemClipboard().string }.get()

    @Test
    fun `is collapsed at first, shows only the header and loads nothing`(robot: FxRobot) {
        robot.interact { view.open(repo) }
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(view.expanded.get())
        assertNull(view.center)
        assertTrue(robot.lookup(".git-log-table").tryQuery<Node>().isEmpty)
        assertEquals("Git Log", robot.lookup(".git-log-title").queryAs(Label::class.java).text)
        assertTrue(robot.lookup(".git-log-refresh").tryQuery<Node>().isPresent)
        assertEquals(view.header.prefHeight(-1.0), view.prefHeight(-1.0), 0.5)
        assertEquals(emptyList(), loads)
    }

    @Test
    fun `clicking the header expands with one load and focuses the table, clicking again collapses`(robot: FxRobot) {
        robot.interact { view.open(repo) }
        click(robot, view.header)

        assertTrue(view.expanded.get())
        eventually { assertEquals((1..5).map { "Subject $it" }, shownSubjects(robot).take(5)) }
        assertEquals(listOf(0 to 500), loads)
        assertSame<Node?>(view.table, view.scene.focusOwner)
        assertEquals(500, view.table.items.size)

        var focusReturned = 0
        view.returnFocus = { focusReturned++ }
        click(robot, view.header)
        assertFalse(view.expanded.get())
        assertNull(view.center)
        assertEquals(1, focusReturned, "the table had the focus")

        // Refresh in the header neither expands nor loads while collapsed.
        click(robot, robot.lookup(".git-log-refresh").query())
        assertFalse(view.expanded.get())
        robot.interact { view.expanded.set(true) }
        eventually { assertEquals(listOf(0 to 500, 0 to 500), loads.toList()) }

        // Collapsing without the focus in the log leaves the focus alone.
        robot.interact {
            view.header.requestFocus()
            view.expanded.set(false)
        }
        assertEquals(1, focusReturned)
    }

    @Test
    fun `shows Subject, Author and the short localized author date, the subject taking the most room`(robot: FxRobot) {
        openAndExpand(robot)

        val columns = view.table.columns
        assertEquals(listOf("Subject", "Author", "Date"), columns.map { it.text })
        assertEquals("Author 1", columns[1].getCellData(0))
        val date =
            java.time.format.DateTimeFormatter
                .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(60))
        assertEquals(date, columns[2].getCellData(0))
        assertTrue(columns[0].width > columns[1].width + columns[2].width, columns.map { it.width }.toString())

        // A narrower window narrows only the subject. Shrinks before growing back: a window cannot grow past the
        // screen, which on the macOS runners is barely wider than the test's window.
        val start = columns.map { it.width }
        robot.interact { view.scene.window.width -= 300 }
        WaitForAsyncUtils.waitForFxEvents()
        val narrower = columns.map { it.width }
        assertEquals(start[0] - 300, narrower[0], 2.0, "$start -> $narrower")
        assertEquals(start[1], narrower[1], 1.0, "$start -> $narrower")
        assertEquals(start[2], narrower[2], 1.0, "$start -> $narrower")

        // Too narrow for all of them: author and date shrink, the date stays in view.
        robot.interact { view.scene.window.width = 260.0 }
        WaitForAsyncUtils.waitForFxEvents()
        val narrow = columns.map { it.width }
        assertTrue(narrow[1] < start[1] && narrow[2] < start[2], "$start -> $narrow")
        assertTrue(narrow.sum() <= view.table.width, "$narrow in ${view.table.width}")

        robot.interact { view.scene.window.width = 800.0 }
        WaitForAsyncUtils.waitForFxEvents()
        val back = columns.map { it.width }
        for (index in start.indices) assertEquals(start[index], back[index], 1.0, "$start -> $back")

        // Dragging the author's edge takes the room from the date.
        robot.interact { view.table.resizeColumn(columns[1], 30.0) }
        val dragged = columns.map { it.width }
        assertEquals(back[0], dragged[0], 1.0, "$back -> $dragged")
        assertEquals(back[1] + 30, dragged[1], 1.0, "$back -> $dragged")
        assertEquals(back[2] - 30, dragged[2], 1.0, "$back -> $dragged")
    }

    @Test
    fun `scrolling with the wheel loads the next page right away`(robot: FxRobot) {
        openAndExpand(robot)
        val flow = view.table.lookup(".virtual-flow") as VirtualFlow<*>

        // The wheel scrolls by pixels; holding the JavaFX thread until the load ran shows that the scroll itself, not a
        // later layout, asked for it.
        var loadedDuringScroll = emptyList<Pair<Int, Int>>()
        robot.interact {
            while ((flow.lastVisibleCell?.index ?: 0) < 460) flow.scrollPixels(200.0)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (loads.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            loadedDuringScroll = loads.toList()
        }

        assertEquals(listOf(0 to 500, 500 to 500), loadedDuringScroll)
        eventually { assertEquals(1000, view.table.items.size) }
    }

    @Test
    fun `scrolling near the end loads the next page until the last`(robot: FxRobot) {
        openAndExpand(robot)
        assertEquals(listOf(0 to 500), loads)

        robot.interact { view.table.scrollTo(400) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf(0 to 500), loads, "not near the end yet")

        robot.interact { view.table.scrollTo(499) }
        eventually { assertEquals(1000, view.table.items.size) }
        robot.interact { view.table.scrollTo(999) }
        eventually { assertEquals(1200, view.table.items.size) }
        robot.interact { view.table.scrollTo(1199) }
        WaitForAsyncUtils.waitForFxEvents()
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(0 to 500, 500 to 500, 1000 to 500), loads)
        assertEquals(
            "Subject 1200",
            view.table.items
                .last()
                .subject,
        )
    }

    @Test
    fun `Refresh reloads the first page and keeps the selected commit selected`(robot: FxRobot) {
        openAndExpand(robot)
        robot.interact { view.table.selectionModel.select(2) }
        history = commits(1, "new") + commits(1200)

        click(robot, robot.lookup(".git-log-refresh").query())

        eventually {
            assertEquals(
                "new1",
                view.table.items
                    .first()
                    .hash,
            )
        }
        assertEquals("h3", view.table.selectionModel.selectedItem.hash)
        assertEquals(
            listOf(3),
            view.table.selectionModel.selectedIndices
                .toList(),
        )
        assertEquals(500, view.table.items.size)
    }

    @Test
    fun `Copy Revision Number copies the selected hashes in table order, also with Shortcut+C`(robot: FxRobot) {
        openAndExpand(robot)
        val copy =
            view.table.contextMenu.items
                .single()
        assertEquals("Copy Revision Number", copy.text)
        assertTrue(copy.isDisable, "nothing selected")

        robot.interact {
            view.table.selectionModel.select(4)
            view.table.selectionModel.select(1)
        }
        assertFalse(copy.isDisable)
        robot.interact { copy.fire() }
        assertEquals("h2\nh5", clipboard())

        robot.interact {
            Clipboard.getSystemClipboard().clear()
            view.table.selectionModel.clearAndSelect(7)
        }
        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(view.table, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, !mac, false, mac))
        }
        assertEquals("h8", clipboard())
    }

    @Test
    fun `says while loading, for an empty repository and when git fails`(robot: FxRobot) {
        gate = CountDownLatch(1)
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        assertEquals("Loading…", placeholder(robot))
        gate?.countDown()
        gate = null
        eventually { assertEquals(500, view.table.items.size) }

        // A failing refresh keeps the rows and says so in the header.
        history = null
        robot.interact { view.refresh() }
        eventually {
            assertEquals(
                "The git log could not be read.",
                robot.lookup(".git-log-status").queryAs(Label::class.java).text,
            )
        }
        assertEquals(500, view.table.items.size)

        robot.interact { view.open(Path.of("/other")) }
        eventually { assertEquals("The git log could not be read.", placeholder(robot)) }
        assertEquals("", robot.lookup(".git-log-status").queryAs(Label::class.java).text)

        otherHistory = emptyList()
        robot.interact { view.refresh() }
        eventually { assertEquals("No commits yet.", placeholder(robot)) }
    }

    @Test
    fun `results for a folder opened before are dropped`(robot: FxRobot) {
        gate = CountDownLatch(1)
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        otherHistory = commits(3, "o")
        robot.interact { view.open(Path.of("/other")) }
        gate?.countDown()
        gate = null

        eventually { assertEquals(listOf("o1", "o2", "o3"), view.table.items.map { it.hash }) }
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("o1", "o2", "o3"), view.table.items.map { it.hash })
    }
}
