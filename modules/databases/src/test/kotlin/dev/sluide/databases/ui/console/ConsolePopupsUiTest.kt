package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
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
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
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
    private lateinit var workspace: ConnectionWorkspace

    private val catalog =
        object : CompletionCatalog {
            override val defaultSchema = "public"

            override fun schemas() = listOf("public")

            override fun tables(schema: String?) = tables

            override fun columns(
                schema: String?,
                table: String,
            ) = if (table == "users") listOf("id", "name") else emptyList()
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

    private fun fire(
        code: KeyCode,
        ctrl: Boolean = false,
        shortcut: Boolean = false,
        alt: Boolean = false,
        target: Node = editor.node,
    ) = Event.fireEvent(
        target,
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl || (shortcut && !mac), alt, shortcut && mac),
    )

    private fun popups(): List<Popup> = Stage.getWindows().filterIsInstance<Popup>().filter { it.isShowing }

    private fun completionList(robot: FxRobot): ListView<*>? =
        popups().firstNotNullOfOrNull { it.scene.lookup(".completion-list") as? ListView<*> }

    private fun labels(robot: FxRobot): List<String> =
        completionList(robot)?.items?.map { (it as dev.sluide.databases.sql.Completion).label } ?: emptyList()

    private fun type(
        robot: FxRobot,
        text: String,
    ) = robot.interact {
        editor.focus()
        text.forEach { editor.replace(editor.caretOffset, editor.caretOffset, it.toString()) }
    }

    @Test
    fun `Ctrl+Space opens the popup and Enter inserts over the typed prefix`(robot: FxRobot) {
        robot.interact {
            editor.replaceText("select * from us")
            editor.focus()
            editor.selectRange(16, 16)
        }
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertEquals(listOf("users"), labels(robot).filter { it == "users" })
        robot.interact { fire(KeyCode.ENTER) }
        assertEquals("select * from users", editor.text)
        assertTrue(popups().isEmpty())
    }

    @Test
    fun `typing a dot after an alias opens the popup by itself with its columns`(robot: FxRobot) {
        robot.interact {
            editor.replaceText("select * from users u where u")
            editor.focus()
            editor.selectRange(29, 29)
        }
        type(robot, ".")
        assertEquals(listOf("id", "name"), labels(robot))
        robot.interact { fire(KeyCode.DOWN) }
        robot.interact { fire(KeyCode.TAB) }
        assertEquals("select * from users u where u.name", editor.text)
    }

    @Test
    fun `a paste does not open it and Escape closes an open one`(robot: FxRobot) {
        robot.interact {
            editor.focus()
            editor.replace(0, 0, "select * from users u where u.")
        }
        robot.interact { assertTrue(popups().isEmpty()) }
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertTrue(popups().isNotEmpty())
        robot.interact { fire(KeyCode.ESCAPE) }
        assertTrue(popups().isEmpty())
        assertEquals("select * from users u where u.", editor.text)
    }

    @Test
    fun `a finished catalog load refreshes an open popup`(robot: FxRobot) {
        robot.interact {
            editor.replaceText("select * from ")
            editor.focus()
            editor.selectRange(14, 14)
        }
        tables = emptyList()
        robot.interact { fire(KeyCode.SPACE, ctrl = true) }
        assertFalse(labels(robot).contains("users"))
        tables = listOf("users")
        robot.interact { loaded.toList().forEach { it() } }
        assertTrue(labels(robot).contains("users"))
    }

    @Test
    fun `Shortcut+Alt+E opens the history, the filter narrows it and Enter inserts on a new line`(robot: FxRobot) {
        robot.interact {
            editor.replaceText("select 2")
            editor.focus()
            editor.selectRange(8, 8)
            fire(KeyCode.E, shortcut = true, alt = true)
        }
        val popup = popups().single()
        val filter = popup.scene.lookup(".history-filter") as TextField
        val list = popup.scene.lookup(".history-list") as ListView<*>
        assertEquals(3, list.items.size)
        assertEquals("update orders set x = 1", list.items[0])
        robot.interact { filter.text = "USERS where" }
        assertEquals<List<Any?>>(listOf("delete from users\nwhere id = 1"), list.items.toList())
        robot.interact { fire(KeyCode.ENTER, target = filter) }
        assertEquals("select 2\ndelete from users\nwhere id = 1", editor.text)
        assertTrue(popups().isEmpty())
    }
}
