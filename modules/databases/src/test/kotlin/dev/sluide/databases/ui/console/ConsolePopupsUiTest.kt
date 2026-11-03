package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.sql.Completion
import dev.sluide.databases.sql.CompletionCatalog
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.ModelFixture
import dev.sluide.databases.ui.result.ExecutingSession
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Tab
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.util.Subscription
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The completion and history popups of a console, wired in a real connection workspace with a fake catalog and
 * history: keys fired as events, text changed through `editor.replace` as typing does. The decisions are in
 * [CompletionModelTest] and [HistoryFilterTest]; this is what needs a scene. Needs a display, see the `uiTest` task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ConsolePopupsUiTest {
    @TempDir
    lateinit var dir: Path

    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = ExecutingSession(config)
    private val entry = ConnectionEntry(config, "key").apply { connected(fake) }
    private val history = QueryHistory()
    private val mac = System.getProperty("os.name").lowercase().contains("mac")
    private val loaded = mutableListOf<() -> Unit>()
    private var tables = listOf("users", "orders")
    private var columnsLoaded = true
    private lateinit var workspace: ConnectionWorkspace

    private val catalog =
        object : CompletionCatalog {
            override val defaultSchema = "public"

            override fun schemas() = listOf("public")

            override fun tables(schema: String?) = tables

            override fun columns(
                schema: String?,
                table: String,
            ) = if (table == "users" && columnsLoaded) listOf("id", "name") else emptyList()
        }

    @Start
    fun start(stage: Stage) {
        val fixture = ModelFixture(dir)
        history.add("key", "select 1")
        history.add("key", "delete from users\nwhere id = 1")
        history.add("key", "update orders set x = 1")
        workspace =
            ConnectionWorkspace(
                entry,
                fixture.model,
                history,
                { 2 },
                { 100 },
                SimpleStringProperty(""),
                { stage },
                { it.run() },
                catalog = catalog,
                onCatalogLoaded = { listener ->
                    loaded += listener
                    Subscription { loaded -= listener }
                },
            )
        stage.scene = Scene(workspace.node as Parent, 900.0, 600.0)
        stage.show()
    }

    private val pane get() = workspace.consoles[0].pane
    private val editor get() = pane.editor

    private fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        val result = AtomicReference<T>()
        robot.interact { result.set(block()) }
        return result.get()
    }

    private fun keyEvent(
        code: KeyCode,
        ctrl: Boolean = false,
        shortcut: Boolean = false,
        alt: Boolean = false,
    ) = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl || (shortcut && !mac), alt, shortcut && mac)

    private fun fire(
        code: KeyCode,
        ctrl: Boolean = false,
        shortcut: Boolean = false,
        alt: Boolean = false,
        target: Node = editor.node,
    ) = Event.fireEvent(target, keyEvent(code, ctrl, shortcut, alt))

    private fun click(target: Node) =
        Event.fireEvent(
            target,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
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
                true,
                null,
            ),
        )

    private fun popups(): List<Popup> = Stage.getWindows().filterIsInstance<Popup>().filter { it.isShowing }

    private fun completionList(): ListView<*>? =
        popups().firstNotNullOfOrNull { it.scene.lookup(".completion-list") as? ListView<*> }

    private fun labels(): List<String> = completionList()?.items?.map { (it as Completion).label } ?: emptyList()

    private fun place(
        robot: FxRobot,
        text: String,
    ) = robot.interact {
        editor.replaceText(text)
        editor.focus()
        editor.selectRange(text.length, text.length)
    }

    private fun type(
        robot: FxRobot,
        text: String,
    ) = robot.interact {
        editor.focus()
        text.forEach { editor.replace(editor.caretOffset, editor.caretOffset, it.toString()) }
    }

    @Test
    fun `Ctrl+Space opens the popup and Enter inserts over the typed prefix`(robot: FxRobot) {
        place(robot, "select * from us")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertTrue("users" in fx(robot) { labels() })
        robot.interact { fire(KeyCode.ENTER) }
        assertEquals("select * from users", fx(robot) { editor.text })
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `Shortcut+Enter closes an open popup and leaves the text to the run`(robot: FxRobot) {
        place(robot, "select * from us")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertTrue(fx(robot) { popups().isNotEmpty() })
        robot.interact { fire(KeyCode.ENTER, shortcut = true) }
        assertTrue(fx(robot) { popups().isEmpty() })
        assertEquals("select * from us", fx(robot) { editor.text })
        // The key went on to the console: the statement ran.
        assertEquals(listOf("select * from us"), fake.executed.map { it.first })
    }

    @Test
    fun `Shift while typing capitals keeps the popup open`(robot: FxRobot) {
        place(robot, "select * from us")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        // Shift+E types a capital; the popup stays.
        robot.interact {
            Event.fireEvent(editor.node, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.E, true, false, false, false))
        }
        assertTrue(fx(robot) { popups().isNotEmpty() })
    }

    @Test
    fun `typing a dot after an alias opens the popup by itself with its columns`(robot: FxRobot) {
        place(robot, "select * from users u where u")
        type(robot, ".")
        assertEquals(listOf("id", "name"), fx(robot) { labels() })
        robot.interact { fire(KeyCode.DOWN) }
        robot.interact { fire(KeyCode.TAB) }
        assertEquals("select * from users u where u.name", fx(robot) { editor.text })
    }

    @Test
    fun `the completion list carries the console stylesheet for its icons`(robot: FxRobot) {
        place(robot, "select * from us")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertTrue(fx(robot) { completionList()?.stylesheets.orEmpty().any { it.endsWith("console.css") } })
    }

    @Test
    fun `a paste does not open it and Escape closes an open one`(robot: FxRobot) {
        robot.interact {
            editor.focus()
            editor.replace(0, 0, "select * from users u where u.")
        }
        assertTrue(fx(robot) { popups().isEmpty() })
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertTrue(fx(robot) { popups().isNotEmpty() })
        robot.interact { fire(KeyCode.ESCAPE) }
        assertTrue(fx(robot) { popups().isEmpty() })
        assertEquals("select * from users u where u.", fx(robot) { editor.text })
    }

    @Test
    fun `moving the caret after typing into an open popup and Escape does not open it again`(robot: FxRobot) {
        place(robot, "select * from users u where u.")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertEquals(listOf("id", "name"), fx(robot) { labels() })
        type(robot, "na")
        assertEquals(listOf("name"), fx(robot) { labels() })
        robot.interact { fire(KeyCode.ESCAPE) }
        assertTrue(fx(robot) { popups().isEmpty() })
        robot.interact { editor.selectRange(editor.caretOffset - 1, editor.caretOffset - 1) }
        robot.interact { editor.selectRange(editor.caretOffset + 1, editor.caretOffset + 1) }
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `a finished load does not open it while the editor has lost the focus`(robot: FxRobot) {
        place(robot, "select * from users u where u")
        columnsLoaded = false
        type(robot, ".")
        robot.interact { workspace.addButton.requestFocus() }
        columnsLoaded = true
        robot.interact { loaded.toList().forEach { it() } }
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `a popup closed by its own auto-hide is not refreshed by a load`(robot: FxRobot) {
        place(robot, "select * from us")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        robot.interact { popups().single().hide() }
        robot.interact { loaded.toList().forEach { it() } }
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `while closed the keys are not taken from the editor`(robot: FxRobot) {
        val taken =
            fx(robot) {
                listOf(KeyCode.UP, KeyCode.DOWN, KeyCode.ENTER, KeyCode.TAB, KeyCode.ESCAPE)
                    .filter { pane.keyHook(keyEvent(it)) }
            }
        assertEquals(emptyList(), taken)
    }

    @Test
    fun `losing the focus closes the popup`(robot: FxRobot) {
        place(robot, "select * from us")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertTrue(fx(robot) { popups().isNotEmpty() })
        robot.interact { workspace.addButton.requestFocus() }
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `a click on the empty space of the list inserts nothing, a click on an item does`(robot: FxRobot) {
        place(robot, "select * from us")
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        robot.interact { click(requireNotNull(completionList())) }
        assertEquals("select * from us", fx(robot) { editor.text })
        assertTrue(fx(robot) { popups().isNotEmpty() })
        robot.interact {
            click(
                requireNotNull(
                    completionList(),
                ).lookupAll(".list-cell").filterIsInstance<ListCell<*>>().first { !it.isEmpty },
            )
        }
        assertTrue(fx(robot) { editor.text.startsWith("select * from ") && editor.text != "select * from us" })
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `a finished catalog load refreshes an open popup`(robot: FxRobot) {
        place(robot, "select * from ")
        tables = emptyList()
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertFalse("users" in fx(robot) { labels() })
        tables = listOf("users")
        robot.interact { loaded.toList().forEach { it() } }
        assertTrue("users" in fx(robot) { labels() })
    }

    @Test
    fun `a dot that found nothing opens the popup when the load finishes with the caret still there`(robot: FxRobot) {
        place(robot, "select * from users u where u")
        columnsLoaded = false
        type(robot, ".")
        assertTrue(fx(robot) { popups().isEmpty() })
        columnsLoaded = true
        robot.interact { loaded.toList().forEach { it() } }
        assertEquals(listOf("id", "name"), fx(robot) { labels() })
    }

    @Test
    fun `a finished load does not open it once the caret moved on`(robot: FxRobot) {
        place(robot, "select * from users u where u")
        columnsLoaded = false
        type(robot, ".")
        robot.interact { editor.selectRange(3, 3) }
        columnsLoaded = true
        robot.interact { loaded.toList().forEach { it() } }
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `closing a console releases its catalog subscription`(robot: FxRobot) {
        robot.interact { workspace.add() }
        assertEquals(2, fx(robot) { loaded.size })
        robot.interact {
            val tab = workspace.consoles[1].tab
            workspace.tabs.tabs.remove(tab)
            Event.fireEvent(tab, Event(Tab.CLOSED_EVENT))
        }
        assertEquals(1, fx(robot) { loaded.size })
    }

    @Test
    fun `Shortcut+Alt+E opens the history, the filter narrows it and Enter inserts on a new line`(robot: FxRobot) {
        place(robot, "select 2")
        robot.interact { fire(KeyCode.E, shortcut = true, alt = true) }
        val popup = fx(robot) { popups().single() }
        val filter = popup.scene.lookup(".history-filter") as TextField
        val list = popup.scene.lookup(".history-list") as ListView<*>
        assertEquals(3, fx(robot) { list.items.size })
        assertEquals("update orders set x = 1", fx(robot) { list.items[0] })
        robot.interact { filter.text = "USERS where" }
        assertEquals<List<Any?>>(listOf("delete from users\nwhere id = 1"), fx(robot) { list.items.toList() })
        robot.interact { fire(KeyCode.ENTER, target = filter) }
        assertEquals("select 2\ndelete from users\nwhere id = 1", fx(robot) { editor.text })
        assertTrue(fx(robot) { popups().isEmpty() })
    }

    @Test
    fun `a click on the empty space of the history list inserts nothing`(robot: FxRobot) {
        place(robot, "select 2")
        robot.interact { fire(KeyCode.E, shortcut = true, alt = true) }
        val list = fx(robot) { popups().single().scene.lookup(".history-list") as ListView<*> }
        robot.interact {
            list.selectionModel.select(0)
            click(list)
        }
        assertEquals("select 2", fx(robot) { editor.text })
        assertTrue(fx(robot) { popups().isNotEmpty() })
    }
}
