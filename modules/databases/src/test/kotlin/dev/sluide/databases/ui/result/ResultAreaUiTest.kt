package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.ConnectionEntry
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The result area in a scene: tabs, pinning, paging through the cursor, data tabs and copy. Their logic is in
 * [ResultLogicTest]; this is the wiring that needs controls. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ResultAreaUiTest {
    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = ExecutingSession(config)
    private val entry = ConnectionEntry(config, "k").apply { connected(fake) }
    private val copied = mutableListOf<String>()
    private lateinit var area: ResultArea

    private val columns = listOf(column("id", "users", "int4"), column("name", "users"))
    private val data = (1..5).map { listOf<Any?>(it, "n$it") }

    @Start
    fun start(stage: Stage) {
        area = ResultArea(entry, { 2 }, { 100 }, clipboard = { copied += it })
        stage.scene = Scene(area.node as javafx.scene.Parent, 600.0, 400.0)
        stage.show()
    }

    private fun rows(
        sql: String = "select * from users",
        cursor: FakeCursor = FakeCursor(columns, data),
    ) = StatementOutcome.Rows(sql, 5, columns, cursor, cursor.firstPage())

    private fun pane() = area.node as TabPane

    private fun tabTexts() = pane().tabs.map { it.text }

    private fun grid(robot: FxRobot): javafx.scene.control.TableView<*> =
        robot.lookup(".result-grid").queryAll<Node>().filterIsInstance<javafx.scene.control.TableView<*>>().first {
            it.scene != null && it.isVisible && it.parent?.parent?.isVisible == true
        }

    @Test
    fun `show creates a result tab with the rows and replaces it unless pinned`(robot: FxRobot) {
        robot.interact { area.show(rows()) }
        assertEquals(listOf("Output", "users"), tabTexts())
        assertEquals(2, grid(robot).items.size)

        robot.interact { area.show(rows("select 2")) }
        assertEquals(2, pane().tabs.size)

        robot.interact {
            (pane().tabs[1] as ResultTab).setPinned(true)
            area.show(rows("select 3"))
        }
        assertEquals(listOf("Output", "users", "users"), tabTexts())
        assertTrue((pane().tabs[1] as ResultTab).pinned)
        assertEquals(pane().tabs[2], pane().selectionModel.selectedItem)
    }

    @Test
    fun `next page asks the cursor for page 1 and shows it`(robot: FxRobot) {
        val cursor = FakeCursor(columns, data)
        robot.interact { area.show(rows(cursor = cursor)) }
        robot.interact {
            robot
                .lookup(".result-button")
                .queryAll<javafx.scene.control.Button>()
                .toList()[2]
                .fire()
        }
        assertEquals(listOf(1), cursor.asked)
        assertEquals(listOf(3, 4), grid(robot).items.map { (it as List<*>)[0] })
    }

    @Test
    fun `openData selects with WHERE and ORDER BY from the fields`(robot: FxRobot) {
        val ref = TableRef(DatabaseKind.POSTGRESQL, "public", "users")
        fake.answer = { sql -> rows(sql) }
        robot.interact { area.openData(ref) }
        assertEquals(
            "SELECT * FROM \"public\".\"users\"",
            fake.executed
                .last()
                .first
                .trim(),
        )
        robot.interact {
            val fields = (pane().selectionModel.selectedItem.content as Node).lookupAll(".text-field")
            val texts = fields.filterIsInstance<TextField>().filter { it.promptText in setOf("WHERE", "ORDER BY") }
            texts.first { it.promptText == "WHERE" }.text = "id > 1"
            texts.first { it.promptText == "ORDER BY" }.apply {
                text = "id DESC"
                onAction.handle(javafx.event.ActionEvent())
            }
        }
        assertEquals(
            "SELECT * FROM \"public\".\"users\"\nWHERE (id > 1\n)\nORDER BY id DESC\n",
            fake.executed.last().first,
        )
        assertEquals(2, fake.executed.last().second)
        robot.interact { area.openData(ref) }
        assertEquals(listOf("Output", "users"), tabTexts())
    }

    @Test
    fun `openData without a session logs an error`(robot: FxRobot) {
        val other = ConnectionEntry(config, "k2")
        lateinit var closedArea: ResultArea
        robot.interact {
            closedArea = ResultArea(other, { 2 }, { 100 })
            closedArea.openData(TableRef(DatabaseKind.POSTGRESQL, null, "t"))
        }
        assertEquals(1, (closedArea.node as TabPane).tabs.size)
    }

    @Test
    fun `Shortcut+C copies the selected cells as TSV`(robot: FxRobot) {
        robot.interact { area.show(rows()) }
        val table = grid(robot) as javafx.scene.control.TableView<List<Any?>>
        robot.interact {
            table.selectionModel.select(0, table.columns[1])
            table.selectionModel.select(1, table.columns[2])
            val key = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false)
            table.fireEvent(key.copyFor(table, table))
        }
        assertEquals(listOf("1\tn1\n2\tn2"), copied)
    }
}
