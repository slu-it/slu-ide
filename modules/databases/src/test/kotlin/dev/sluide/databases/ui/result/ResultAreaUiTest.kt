package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.ConnectionEntry
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The result area in a scene: tabs, pinning, cursor lifetime, paging through the cursor, stale answers, data tabs
 * and copy. Their logic is in [ResultLogicTest]; this is the wiring that needs controls. Needs a display, see the
 * `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ResultAreaUiTest {
    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = ExecutingSession(config)
    private val entry = ConnectionEntry(config, "k").apply { connected(fake) }
    private val copied = mutableListOf<String>()
    private lateinit var area: ResultArea
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private val columns = listOf(column("id", "users", "int4"), column("name", "users"))
    private val data = (1..5).map { listOf<Any?>(it, "n$it") }

    @Start
    fun start(stage: Stage) {
        area = ResultArea(entry, { 2 }, { 100 }, clipboard = { copied += it })
        stage.scene = Scene(area.node as Parent, 600.0, 400.0)
        stage.show()
    }

    private fun cursor(first: Int = 1) = FakeCursor(columns, (first until first + 5).map { listOf<Any?>(it, "n$it") })

    private fun rows(
        sql: String = "select * from users",
        cursor: FakeCursor = cursor(),
    ) = StatementOutcome.Rows(sql, 5, columns, cursor, cursor.firstPage())

    private fun pane() = area.node as TabPane

    private fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        val result = AtomicReference<T>()
        robot.interact { result.set(block()) }
        return result.get()
    }

    private fun resultTab(index: Int) = pane().tabs[index] as ResultTab

    private fun tabTexts(robot: FxRobot) = fx(robot) { pane().tabs.map { it.text } }

    @Suppress("UNCHECKED_CAST")
    private fun gridOf(tab: Tab) = tab.content.lookup(".result-grid") as TableView<List<Any?>>

    private fun ids(
        robot: FxRobot,
        tab: Tab,
    ) = fx(robot) { gridOf(tab).items.map { it[0] } }

    private fun button(
        tab: Tab,
        vararg tips: String,
    ) = tab.content
        .lookupAll(".result-button")
        .filterIsInstance<Button>()
        .first { it.tooltip.text in tips }

    private fun status(tab: Tab) = (tab.content.lookup(".result-status") as Label).text

    private fun field(
        tab: Tab,
        prompt: String,
    ) = tab.content
        .lookupAll(".text-field")
        .filterIsInstance<TextField>()
        .first { it.promptText == prompt }

    private fun pageField(tab: Tab) =
        tab.content.lookupAll(".text-field").filterIsInstance<TextField>().first {
            it.tooltip?.text == "Rows per page"
        }

    @Suppress("UNCHECKED_CAST")
    private fun output(robot: FxRobot) = fx(robot) { (pane().tabs[0].content as ListView<OutputLine>).items.toList() }

    private fun pressCopy(node: Node) {
        Event.fireEvent(node, KeyEvent(KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C, false, !mac, false, mac))
    }

    private fun click(label: Node) {
        Event.fireEvent(
            label,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0.0,
                0.0,
                0.0,
                0.0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    @Test
    fun `show creates a result tab with the rows and replaces it unless pinned`(robot: FxRobot) {
        robot.interact { area.show(rows()) }
        assertEquals(listOf("Output", "users"), tabTexts(robot))
        assertEquals(listOf<Any?>(1, 2), ids(robot, resultTab(1)))

        robot.interact { area.show(rows("select 2")) }
        assertEquals(2, fx(robot) { pane().tabs.size })

        robot.interact {
            resultTab(1).setPinned(true)
            area.show(rows("select 3"))
        }
        assertEquals(listOf("Output", "users", "users"), tabTexts(robot))
        assertTrue(fx(robot) { resultTab(1).pinned })
        assertEquals(fx(robot) { pane().tabs[2] }, fx(robot) { pane().selectionModel.selectedItem })
    }

    @Test
    fun `cursors close with their tab, when replaced, with the area and with Close Other Result Tabs`(robot: FxRobot) {
        val first = cursor()
        robot.interact { area.show(rows(cursor = first)) }
        val second = cursor()
        robot.interact { area.show(rows(cursor = second)) }
        assertTrue(first.closed, "replaced content closes its cursor")
        assertFalse(second.closed)

        robot.interact {
            val tab = resultTab(1)
            Event.fireEvent(tab, Event(Tab.CLOSED_EVENT))
            pane().tabs.remove(tab)
        }
        assertTrue(second.closed, "a closed tab closes its cursor")

        val pinned = cursor()
        val plain = cursor()
        val keeper = cursor()
        robot.interact {
            area.show(rows(cursor = pinned))
            resultTab(1).setPinned(true)
            area.show(rows(cursor = plain), replaceUnpinned = false)
            area.show(rows(cursor = keeper), replaceUnpinned = false)
        }
        robot.interact {
            pane()
                .tabs
                .filterIsInstance<ResultTab>()
                .last()
                .contextMenu.items[1]
                .fire()
        }
        assertTrue(plain.closed, "Close Other closes the others")
        assertFalse(pinned.closed, "a pinned tab is kept")
        assertFalse(keeper.closed)

        robot.interact { area.close() }
        assertTrue(keeper.closed)
        assertTrue(pinned.closed)
    }

    @Test
    fun `show after close works again and the fetched page stays viewable`(robot: FxRobot) {
        robot.interact { area.show(rows()) }
        robot.interact { area.close() }
        assertEquals(listOf<Any?>(1, 2), ids(robot, resultTab(1)))
        robot.interact { area.show(rows("select 2", cursor(10))) }
        assertEquals(listOf<Any?>(10, 11), ids(robot, resultTab(1)))
    }

    @Test
    fun `next page asks the cursor for page 1 and shows it`(robot: FxRobot) {
        val cursor = cursor()
        robot.interact { area.show(rows(cursor = cursor)) }
        robot.interact { button(resultTab(1), "Next Page").fire() }
        assertEquals(listOf(1), cursor.asked)
        assertEquals(listOf<Any?>(3, 4), ids(robot, resultTab(1)))
    }

    @Test
    fun `last asks for the last page with the fetch-all cap`(robot: FxRobot) {
        val cursor = cursor()
        robot.interact { area.show(rows(cursor = cursor)) }
        robot.interact { button(resultTab(1), "Last Page").fire() }
        assertEquals(listOf(100), cursor.lastCaps)
        assertEquals(listOf<Any?>(5), ids(robot, resultTab(1)))
    }

    @Test
    fun `page size field resizes the cursor, shows its page and writes the size back`(robot: FxRobot) {
        val cursor = cursor()
        robot.interact { area.show(rows(cursor = cursor)) }
        assertEquals("2", fx(robot) { pageField(resultTab(1)).text })
        assertEquals("2", fx(robot) { pageField(resultTab(1)).promptText })
        robot.interact {
            val f = pageField(resultTab(1))
            f.text = "5"
            f.onAction.handle(javafx.event.ActionEvent())
        }
        // 5 is below the lowest page size, which is 10
        assertEquals(listOf(10), cursor.sizes)
        assertEquals(listOf(0), cursor.derived.single().asked)
        assertEquals("10", fx(robot) { pageField(resultTab(1)).text })
        assertEquals(listOf<Any?>(1, 2, 3, 4, 5), ids(robot, resultTab(1)))
        assertEquals("1–5 of 5", fx(robot) { (resultTab(1).content.lookup(".result-page-label") as Label).text })
    }

    @Test
    fun `a failed resize keeps the old cursor and the field shows its size`(robot: FxRobot) {
        val cursor = cursor()
        robot.interact { area.show(rows(cursor = cursor)) }
        cursor.failure = IllegalStateException("boom")
        robot.interact {
            val f = pageField(resultTab(1))
            f.text = "50"
            f.onAction.handle(javafx.event.ActionEvent())
        }
        assertEquals("2", fx(robot) { pageField(resultTab(1)).text })
        assertEquals(listOf<Any?>(1, 2), ids(robot, resultTab(1)))
        cursor.failure = null
        robot.interact { button(resultTab(1), "Next Page").fire() }
        assertEquals(listOf(1), cursor.asked, "the old cursor is still the one in use")
        assertEquals(listOf<Any?>(3, 4), ids(robot, resultTab(1)))
    }

    @Test
    fun `the fields are off while loading`(robot: FxRobot) {
        val ref = TableRef(DatabaseKind.POSTGRESQL, null, "users")
        fake.defer = true
        robot.interact { area.openData(ref) }
        val tab = resultTab(1)
        assertTrue(fx(robot) { field(tab, "WHERE").isDisable && field(tab, "ORDER BY").isDisable })
        assertTrue(fx(robot) { pageField(tab).isDisable })
        fake.deferred.single().complete(rows("select"))
        robot.interact { }
        assertFalse(fx(robot) { field(tab, "WHERE").isDisable || pageField(tab).isDisable })
    }

    @Test
    fun `a paging error shows in the status and Output and enables the buttons again`(robot: FxRobot) {
        val cursor = cursor()
        robot.interact { area.show(rows(cursor = cursor)) }
        cursor.failure = IllegalStateException("boom")
        robot.interact { button(resultTab(1), "Next Page").fire() }
        assertEquals("Page failed: boom", fx(robot) { status(resultTab(1)) })
        assertFalse(fx(robot) { button(resultTab(1), "Next Page").isDisable })
        assertTrue(output(robot).any { it.error && it.text.endsWith("Page failed: boom") })
    }

    @Test
    fun `while a page loads the buttons are off and the status says so`(robot: FxRobot) {
        val cursor = cursor()
        cursor.manual = true
        robot.interact { area.show(rows(cursor = cursor)) }
        robot.interact { button(resultTab(1), "Next Page").fire() }
        assertTrue(fx(robot) { button(resultTab(1), "Next Page").isDisable })
        assertEquals("Loading…", fx(robot) { status(resultTab(1)) })
    }

    @Test
    fun `a late page of replaced content is ignored`(robot: FxRobot) {
        val old = cursor()
        old.manual = true
        robot.interact { area.show(rows(cursor = old)) }
        robot.interact { button(resultTab(1), "Next Page").fire() }
        robot.interact { area.show(rows("select 2", cursor(10))) }
        old.pending
            .single()
            .second
            .complete(old.pageOf(1))
        robot.interact { }
        assertEquals(listOf<Any?>(10, 11), ids(robot, resultTab(1)))
    }

    @Test
    fun `a late reload answer of replaced content is ignored and its cursor closed`(robot: FxRobot) {
        robot.interact { area.show(rows()) }
        fake.defer = true
        robot.interact { button(resultTab(1), "Reload").fire() }
        robot.interact { area.show(rows("select 2", cursor(10))) }
        val late = cursor(20)
        fake.deferred.single().complete(rows("select * from users", late))
        robot.interact { }
        assertTrue(late.closed)
        assertEquals(listOf<Any?>(10, 11), ids(robot, resultTab(1)))
    }

    @Test
    fun `reload runs the same SQL with the page size of the field`(robot: FxRobot) {
        fake.answer = { rows(it) }
        robot.interact { area.show(rows("select * from users where id > 0")) }
        robot.interact { pageField(resultTab(1)).text = "20" }
        robot.interact { button(resultTab(1), "Reload").fire() }
        assertEquals("select * from users where id > 0" to 20, fake.executed.last())
    }

    @Test
    fun `reload is off in a console tab for a statement that changes data`(robot: FxRobot) {
        robot.interact { area.show(rows("insert into t values (1) returning id")) }
        assertTrue(fx(robot) { button(resultTab(1), RELOAD_OFF_TIP).isDisable })
        robot.interact { area.show(rows("select 1")) }
        assertFalse(fx(robot) { button(resultTab(1), "Reload").isDisable })
    }

    @Test
    fun `a header click on a data tab sorts on the server, ascending then descending`(robot: FxRobot) {
        val ref = TableRef(DatabaseKind.POSTGRESQL, "public", "users")
        fake.answer = { rows(it) }
        robot.interact { area.openData(ref) }
        val tab = resultTab(1)

        fun header() = gridOf(tab).columns[1].graphic as Label
        robot.interact { click(header()) }
        assertEquals("\"id\" ASC", fx(robot) { field(tab, "ORDER BY").text })
        assertTrue(
            fake.executed
                .last()
                .first
                .endsWith("ORDER BY \"id\" ASC\n"),
        )
        assertEquals("id ▲", fx(robot) { header().text })
        robot.interact { click(header()) }
        assertEquals("\"id\" DESC", fx(robot) { field(tab, "ORDER BY").text })
        assertTrue(
            fake.executed
                .last()
                .first
                .endsWith("ORDER BY \"id\" DESC\n"),
        )
        assertEquals("id ▼", fx(robot) { header().text })
        robot.interact {
            field(tab, "ORDER BY").text = "\"id\" DESC, \"name\""
            field(tab, "ORDER BY").onAction.handle(javafx.event.ActionEvent())
        }
        assertEquals("id", fx(robot) { header().text }, "no arrow for an ORDER BY that is not one column's sort")
    }

    @Test
    fun `openData selects with WHERE and ORDER BY from the fields and reuses its tab`(robot: FxRobot) {
        val ref = TableRef(DatabaseKind.POSTGRESQL, "public", "users")
        fake.answer = { rows(it) }
        robot.interact { area.openData(ref) }
        assertEquals(
            "SELECT * FROM \"public\".\"users\"",
            fake.executed
                .last()
                .first
                .trim(),
        )
        assertEquals(2, fake.executed.last().second)
        robot.interact {
            field(resultTab(1), "WHERE").text = "id > 1"
            val order = field(resultTab(1), "ORDER BY")
            order.text = "id DESC"
            order.onAction.handle(javafx.event.ActionEvent())
        }
        assertEquals(
            "SELECT * FROM \"public\".\"users\"\nWHERE (id > 1\n)\nORDER BY id DESC\n",
            fake.executed.last().first,
        )
        robot.interact { area.openData(ref) }
        assertEquals(listOf("Output", "users"), tabTexts(robot))
    }

    @Test
    fun `openData without a session logs an error and opens no tab`(robot: FxRobot) {
        val other = ConnectionEntry(config, "k2")
        lateinit var closedArea: ResultArea
        robot.interact {
            closedArea = ResultArea(other, { 2 }, { 100 })
            closedArea.openData(TableRef(DatabaseKind.POSTGRESQL, null, "t"))
        }
        val pane = closedArea.node as TabPane
        assertEquals(1, fx(robot) { pane.tabs.size })
        @Suppress("UNCHECKED_CAST")
        val lines = fx(robot) { (pane.tabs[0].content as ListView<OutputLine>).items.toList() }
        assertTrue(lines.single().error && lines.single().text.endsWith("Cannot open t: not connected"))
    }

    @Test
    fun `Shortcut+C copies the selected cells as TSV and Copy with Header needs a selection`(robot: FxRobot) {
        robot.interact { area.show(rows()) }
        val table = gridOf(resultTab(1))
        robot.interact { pressCopy(table) }
        assertTrue(copied.isEmpty())
        robot.interact { table.contextMenu.items[1].fire() }
        assertTrue(copied.isEmpty(), "Copy with Header leaves the clipboard alone without a selection")
        robot.interact {
            table.selectionModel.select(0, table.columns[1])
            table.selectionModel.select(1, table.columns[2])
            pressCopy(table)
        }
        assertEquals(listOf("1\tn1\n2\tn2"), copied)
    }

    @Test
    fun `Output keeps 1000 lines and copies the selected ones`(robot: FxRobot) {
        robot.interact { repeat(1100) { area.log("line $it") } }
        val lines = output(robot)
        assertEquals(1000, lines.size)
        assertTrue(lines.first().text.endsWith("line 100"))
        robot.interact {
            @Suppress("UNCHECKED_CAST")
            val list = pane().tabs[0].content as ListView<OutputLine>
            list.selectionModel.selectIndices(1, 0)
            pressCopy(list)
        }
        assertEquals(1, copied.size)
        assertEquals(listOf("line 100", "line 101"), copied.single().lines().map { it.substringAfter("  ") })
    }
}
