package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.FakeSession
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Menu
import javafx.scene.control.MenuButton
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A session whose edit submits run on a recording connection. */
private class EditingSession(
    config: ConnectionConfig,
    counts: (String) -> Int = { 1 },
    autoCommit: Boolean = true,
) : FakeSession(config) {
    val jdbc = RecordingJdbc(startAutoCommit = autoCommit, counts = counts)
    val changes = mutableListOf<Boolean>()
    val executed = mutableListOf<String>()

    /** The answer of a reload (the statement is run again after a submit). */
    var reloaded: (String) -> StatementOutcome = { error("no reload expected: $it") }

    /** While set, a submit does not run until [release]. */
    var hold = false
    private var held: (() -> Unit)? = null

    fun release() = held?.invoke().also { held = null }

    override fun execute(
        sql: String,
        pageSize: Int,
    ): CompletableFuture<StatementOutcome> {
        executed += sql
        return CompletableFuture.completedFuture(reloaded(sql))
    }

    override fun <T> withSession(
        changes: Boolean,
        work: (Connection) -> T,
    ): CompletableFuture<T> {
        this.changes += changes
        if (hold) {
            val future = CompletableFuture<T>()
            held = { future.complete(work(jdbc.connection)) }
            return future
        }
        return try {
            CompletableFuture.completedFuture(work(jdbc.connection))
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }
}

/**
 * Editing, Submit, export, the value viewer and transpose of the result grid in a scene. Their logic is in
 * [EditSubmitterTest], `DataEditsTest` and [ResultLogicTest]; this is the wiring that needs controls. Needs a display,
 * see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ResultEditingUiTest {
    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = EditingSession(config)
    private val rejecting = EditingSession(config, { 0 })
    private var lastCursor = FakeCursor(emptyList(), emptyList())
    private val entry = ConnectionEntry(config, "k").apply { connected(fake) }
    private val scratch = mutableListOf<Pair<String, String>>()
    private lateinit var area: ResultArea
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private val columns = listOf(column("id", "users", "int4"), column("name", "users"))

    @Start
    fun start(stage: Stage) {
        area = ResultArea(entry, { 2 }, { 100 }, clipboard = {}, openScratch = { ext, text -> scratch += ext to text })
        area.primaryKeyLookup = { _, _ -> CompletableFuture.completedFuture(listOf("id")) }
        stage.scene = Scene(area.node as Parent, 700.0, 400.0)
        stage.show()
    }

    private fun rows(
        cols: List<ColumnInfo> = columns,
        sql: String = "select * from users",
    ): StatementOutcome.Rows {
        val cursor = FakeCursor(cols, listOf(listOf<Any?>(1, "a"), listOf<Any?>(2, "b"), listOf<Any?>(3, "c")))
        lastCursor = cursor
        return StatementOutcome.Rows(sql, 5, cols, cursor, cursor.firstPage())
    }

    /** The result of the statement run again after a submit: the rows as they are in the database then. */
    private fun reloadsWith(
        session: EditingSession,
        name: String,
    ) {
        session.reloaded = { sql ->
            val cursor = FakeCursor(columns, listOf(listOf<Any?>(1, name), listOf<Any?>(2, "b"), listOf<Any?>(3, "c")))
            StatementOutcome.Rows(sql, 5, columns, cursor, cursor.firstPage())
        }
    }

    private fun showRows(
        robot: FxRobot,
        cols: List<ColumnInfo> = columns,
    ): ResultTab {
        robot.interact { area.show(rows(cols)) }
        robot.interact { } // the answer of the key lookup comes through runLater
        return tab()
    }

    private fun tab() = (area.node as TabPane).tabs[1] as ResultTab

    private fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        val result = AtomicReference<T>()
        robot.interact { result.set(block()) }
        return result.get()
    }

    @Suppress("UNCHECKED_CAST")
    private fun grid() = tab().content.lookup(".result-grid") as TableView<List<Any?>>

    private fun button(tip: String) =
        tab()
            .content
            .lookupAll(".result-button")
            .filterIsInstance<Button>()
            .first { it.tooltip.text == tip }

    /** The paging bar's buttons in order: first, previous, next, last, Reload. */
    private fun pagingButton(index: Int) =
        tab()
            .content
            .lookupAll(".result-button")
            .filterIsInstance<Button>()
            .toList()[index]

    private fun pageField() =
        tab()
            .content
            .lookupAll(".text-field")
            .filterIsInstance<TextField>()
            .first { it.promptText == "2" }

    private fun editors() = grid().lookupAll(".text-field").filterIsInstance<TextField>()

    private fun status() = (tab().content.lookup(".result-status") as Label).text

    private fun key(
        code: KeyCode,
        shift: Boolean = false,
        shortcut: Boolean = false,
    ) = Event.fireEvent(
        grid(),
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, false, shortcut && mac),
    )

    private fun selectCell(
        row: Int,
        column: Int,
    ) {
        val table = grid()
        table.selectionModel.clearSelection()
        table.selectionModel.select(row, table.columns[column + 1])
        table.focusModel.focus(row, table.columns[column + 1])
    }

    private fun editName(
        robot: FxRobot,
        row: Int,
        text: String,
    ) = editCell(robot, row, 1, text)

    private fun editCell(
        robot: FxRobot,
        row: Int,
        column: Int,
        text: String,
    ) {
        robot.interact {
            selectCell(row, column)
            key(KeyCode.ENTER)
        }
        robot.interact {
            val field = editors().first()
            field.text = text
            field.onAction.handle(ActionEvent())
        }
    }

    private fun submitButton() = button("Submit (Shortcut+Enter)")

    private fun lines(): List<String> =
        ((area.node as TabPane).tabs[0].content.lookupAll(".list-cell"))
            .mapNotNull { (it as ListCell<*>).text }

    @Test
    fun `an editable result shows the edit buttons and a read-only one the lock with the reason`(robot: FxRobot) {
        showRows(robot)
        val tips = listOf("Add Row", "Delete Rows", "Set NULL", "Revert", "Submit (Shortcut+Enter)")
        assertTrue(fx(robot) { tips.all { button(it).isVisible } })
        assertTrue(fx(robot) { button("Submit (Shortcut+Enter)").isDisable }, "Submit waits for an edit")
        assertFalse(fx(robot) { tab().content.lookup(".result-lock").isVisible })

        robot.interact { area.show(rows(listOf(column("id", "users"), column("x", "other"))), replaceUnpinned = true) }
        robot.interact { }
        assertFalse(fx(robot) { button("Add Row").isVisible })
        val lock = fx(robot) { tab().content.lookup(".result-lock") as Label }
        assertTrue(fx(robot) { lock.isVisible })
        assertEquals("The result comes from several tables or expressions", fx(robot) { lock.tooltip.text })
    }

    @Test
    fun `editing a cell and Submit run the statements on the session and log them`(robot: FxRobot) {
        showRows(robot)
        editName(robot, 0, "zed")
        assertEquals("zed", fx(robot) { grid().items[0][1] })
        assertTrue(area.pendingEdits)
        assertTrue(fx(robot) { grid().lookupAll(".db-cell-changed").isNotEmpty() })

        reloadsWith(fake, "zed")
        robot.interact { key(KeyCode.ENTER, shortcut = true) }
        robot.interact { }
        assertEquals(listOf(true), fake.changes)
        assertEquals(listOf("select * from users"), fake.executed, "the data is loaded again after a submit")
        val log = fake.jdbc.log.toList()
        assertTrue(log.any { it.startsWith("execute UPDATE") && "\"name\" = ?" in it }, "$log")
        assertTrue("setObject 1=zed type=1111" in log, "$log")
        assertTrue("setObject 2=1" in log, "$log")
        assertTrue("autoCommit=false" in log && "commit" in log, "idle Auto mode: a transaction of its own: $log")
        assertFalse(area.pendingEdits)
        assertEquals("zed", fx(robot) { grid().items[0][1] })
        assertEquals("Submitted 1 change", status())
    }

    @Test
    fun `a conflict keeps the edits and shows the error in the status and Output`(robot: FxRobot) {
        robot.interact { entry.connected(rejecting) }
        showRows(robot)
        editName(robot, 0, "zed")
        robot.interact { button("Submit (Shortcut+Enter)").fire() }
        robot.interact { }
        assertTrue(area.pendingEdits)
        assertEquals("The row was changed or deleted by someone else (0 rows matched)", status())
        assertTrue(fx(robot) { lines().any { it.contains("someone else") } })
    }

    @Test
    fun `pending edits disable paging and a new result gets a tab of its own, Revert releases them`(robot: FxRobot) {
        showRows(robot)
        assertFalse(fx(robot) { pagingButton(2).isDisable })
        editName(robot, 0, "zed")
        val tip = "Submit or revert the edits first"
        assertTrue(fx(robot) { pagingButton(2).isDisable && pagingButton(2).tooltip.text == tip })
        assertTrue(fx(robot) { pagingButton(4).isDisable }, "Reload")
        assertFalse(fx(robot) { tab().view.resultGrid.sortEnabled }, "sorting is off")
        assertTrue(
            fx(robot) {
                grid().parent.lookupAll(".text-field").filterIsInstance<TextField>().any {
                    it.tooltip?.text == tip && it.isDisable
                }
            },
        )
        robot.interact { area.show(rows()) }
        assertEquals(3, fx(robot) { (area.node as TabPane).tabs.size })
        assertTrue(area.pendingEdits)
        robot.interact {
            selectCell(0, 1)
            button("Revert").fire()
        }
        assertFalse(area.pendingEdits)
        assertEquals("a", fx(robot) { grid().items[0][1] })
        assertFalse(fx(robot) { pagingButton(2).isDisable })
        assertTrue(fx(robot) { tab().view.resultGrid.sortEnabled })
    }

    @Test
    fun `Open All as Scratch File CSV hands the rows to openScratch`(robot: FxRobot) {
        showRows(robot)
        robot.interact {
            val menu =
                tab()
                    .content
                    .lookupAll(".menu-button")
                    .filterIsInstance<MenuButton>()
                    .first()
            val openAll = menu.items.first { it.text == "Open All as Scratch File" } as Menu
            openAll.items.first { it.text == "CSV" }.fire()
        }
        robot.interact { }
        assertEquals(listOf("csv"), scratch.map { it.first })
        assertTrue(scratch.single().second.startsWith("id,name\r\n1,a\r\n"), scratch.single().second)
    }

    @Test
    fun `Shift+Enter opens the value viewer and Apply writes the text back`(robot: FxRobot) {
        showRows(robot)
        val requests = mutableListOf<ValueRequest>()
        robot.interact {
            tab().view.gridEditing.showValue = {
                requests += it
                "applied"
            }
            selectCell(1, 1)
            key(KeyCode.ENTER, shift = true)
        }
        assertEquals(listOf(ValueRequest("name", "b", true)), requests)
        assertEquals("applied", fx(robot) { grid().items[1][1] })
        assertTrue(area.pendingEdits)
    }

    @Test
    fun `the transpose toggle shows the rows as columns and editing is off`(robot: FxRobot) {
        showRows(robot)
        robot.interact {
            tab()
                .content
                .lookupAll(".toggle-button")
                .filterIsInstance<ToggleButton>()
                .first()
                .fire()
        }
        assertEquals(
            listOf("Column", "1", "2"),
            fx(robot) { grid().columns.drop(1).map { (it.graphic as Label).text } },
        )
        assertEquals(listOf<Any?>("id", 1, 2), fx(robot) { grid().items[0] })
        assertFalse(fx(robot) { button("Add Row").isVisible })
        robot.interact {
            tab()
                .content
                .lookupAll(".toggle-button")
                .filterIsInstance<ToggleButton>()
                .first()
                .fire()
        }
        assertEquals(listOf("id", "name"), fx(robot) { grid().columns.drop(1).map { (it.graphic as Label).text } })
        assertTrue(fx(robot) { button("Add Row").isVisible })
    }

    @Test
    fun `an edit attempt during a held submit changes nothing`(robot: FxRobot) {
        showRows(robot)
        editName(robot, 0, "zed")
        fake.hold = true
        robot.interact { submitButton().fire() }
        robot.interact {
            selectCell(1, 1)
            key(KeyCode.ENTER)
            key(KeyCode.F2)
            key(KeyCode.ENTER, shortcut = true)
        }
        assertTrue(fx(robot) { editors().toList().isEmpty() }, "no editor opens")
        assertTrue(fx(robot) { button("Add Row").isDisable && button("Set NULL").isDisable })
        assertEquals(listOf(true), fake.changes, "the second submit did not start")
        fake.hold = false
        reloadsWith(fake, "zed")
        robot.interact { fake.release() }
        robot.interact { }
        assertFalse(fx(robot) { button("Add Row").isDisable })
    }

    @Test
    fun `an edit attempt during a held page load changes nothing`(robot: FxRobot) {
        showRows(robot)
        lastCursor.manual = true
        robot.interact { pagingButton(2).fire() }
        robot.interact {
            selectCell(0, 1)
            key(KeyCode.ENTER)
        }
        assertTrue(fx(robot) { editors().toList().isEmpty() }, "no editor opens while the page loads")
        assertTrue(fx(robot) { button("Add Row").isDisable && button("Set NULL").isDisable })
        assertFalse(area.pendingEdits)
        robot.interact {
            lastCursor.pending
                .single()
                .second
                .complete(lastCursor.pageOf(1))
        }
        robot.interact { }
        assertFalse(fx(robot) { button("Add Row").isDisable })
    }

    @Test
    fun `transpose turns paging, Reload and the page size off until the next load`(robot: FxRobot) {
        showRows(robot)
        robot.interact { transposeToggle().fire() }
        val tip = "Turn Transpose off first"
        assertTrue(fx(robot) { (0..4).all { pagingButton(it).isDisable } })
        assertEquals(tip, fx(robot) { pagingButton(2).tooltip.text })
        assertTrue(fx(robot) { pageField().isDisable })
        assertFalse(fx(robot) { tab().view.resultGrid.sortEnabled })
        robot.interact { area.show(rows()) }
        assertFalse(fx(robot) { transposeToggle().isSelected }, "a new load resets the toggle")
        assertFalse(fx(robot) { pagingButton(2).isDisable })
        assertFalse(fx(robot) { pageField().isDisable })
    }

    @Test
    fun `the result of a statement that changed data is read-only and is not run again`(robot: FxRobot) {
        robot.interact { area.show(rows(sql = "update users set name = 'x' returning *")) }
        robot.interact { }
        assertFalse(fx(robot) { button("Add Row").isVisible })
        val lock = fx(robot) { tab().content.lookup(".result-lock") as Label }
        assertEquals("The statement changed data; run a SELECT to edit", fx(robot) { lock.tooltip.text })
        robot.interact { tab().view.reload() }
        assertTrue(fake.executed.isEmpty(), "what changes data is never run again")
        assertTrue(fx(robot) { pagingButton(4).isDisable })
    }

    @Test
    fun `closing a tab with pending edits asks and Cancel keeps it, Close Other skips it`(robot: FxRobot) {
        showRows(robot)
        editName(robot, 0, "zed")
        val asked = mutableListOf<String>()
        var discard = false
        area.confirmDiscard = {
            asked += it
            discard
        }
        val event = Event(Tab.TAB_CLOSE_REQUEST_EVENT)
        robot.interact { tab().onCloseRequest.handle(event) }
        assertEquals(listOf("users"), asked)
        assertTrue(event.isConsumed, "Cancel keeps the tab")
        discard = true
        val second = Event(Tab.TAB_CLOSE_REQUEST_EVENT)
        robot.interact { tab().onCloseRequest.handle(second) }
        assertFalse(second.isConsumed, "Discard Edits lets it close")

        robot.interact { area.show(rows(), replaceUnpinned = false) }
        robot.interact { (pane().tabs[2] as ResultTab).contextMenu.items[1].fire() }
        assertEquals(3, fx(robot) { pane().tabs.size }, "the tab with pending edits is kept like a pinned one")
        assertTrue(area.pendingEdits)
    }

    private fun pane() = area.node as TabPane

    private fun transposeToggle() =
        tab()
            .content
            .lookupAll(".toggle-button")
            .filterIsInstance<ToggleButton>()
            .first()

    @Test
    fun `Add Row and Delete Rows keep the grid and the marks in step with the edits`(robot: FxRobot) {
        showRows(robot)
        robot.interact { button("Add Row").fire() }
        robot.interact { }
        assertEquals(3, fx(robot) { grid().items.size })
        assertTrue(fx(robot) { grid().lookupAll(".db-cell-added").isNotEmpty() })
        assertTrue(area.pendingEdits)

        robot.interact {
            selectCell(2, 1)
            button("Delete Rows").fire()
        }
        assertEquals(2, fx(robot) { grid().items.size }, "a row that was only added disappears")
        assertFalse(area.pendingEdits)

        robot.interact {
            selectCell(1, 1)
            button("Delete Rows").fire()
        }
        robot.interact { }
        assertEquals(2, fx(robot) { grid().items.size }, "a deleted row stays, marked")
        assertTrue(fx(robot) { grid().lookupAll(".db-cell-deleted").isNotEmpty() })
        assertTrue(area.pendingEdits)

        robot.interact {
            selectCell(1, 1)
            button("Revert").fire()
        }
        robot.interact { }
        assertTrue(fx(robot) { grid().lookupAll(".db-cell-deleted").isEmpty() })
        assertFalse(area.pendingEdits)
    }

    @Test
    fun `Set NULL clears the selected cell and submits a NULL`(robot: FxRobot) {
        showRows(robot)
        robot.interact {
            selectCell(0, 1)
            button("Set NULL").fire()
        }
        assertEquals(null, fx(robot) { grid().items[0][1] })
        assertTrue(fx(robot) { grid().lookupAll(".db-cell-changed").isNotEmpty() })
        reloadsWith(fake, "x")
        robot.interact { submitButton().fire() }
        robot.interact { }
        assertTrue("setObject 1=null" in fake.jdbc.log, "${fake.jdbc.log}")
    }

    @Test
    fun `a text that does not parse keeps the editor open and shows the message in red`(robot: FxRobot) {
        val numbers =
            listOf(ColumnInfo("id", "id", "int4", java.sql.Types.INTEGER, null, "users", false, false), columns[1])
        showRows(robot, numbers)
        robot.interact {
            selectCell(0, 0)
            key(KeyCode.ENTER)
        }
        robot.interact {
            val field = editors().first()
            field.text = "abc"
            field.onAction.handle(ActionEvent())
        }
        assertEquals(1, fx(robot) { editors().count() }, "the editor stays open")
        assertTrue(
            fx(robot) {
                tab()
                    .content
                    .lookup(".result-status")
                    .styleClass
                    .contains("result-error")
            },
        )
        assertFalse(area.pendingEdits)
    }

    @Test
    fun `in Manual mode the submit uses a savepoint and says it is not committed yet`(robot: FxRobot) {
        val manual = EditingSession(config.copy(commitMode = CommitMode.MANUAL), autoCommit = false)
        manual.commitMode = CommitMode.MANUAL
        reloadsWith(manual, "zed")
        robot.interact { entry.connected(manual) }
        showRows(robot)
        editName(robot, 0, "zed")
        robot.interact { submitButton().fire() }
        robot.interact { }
        assertTrue("setSavepoint" in manual.jdbc.log && "commit" !in manual.jdbc.log, "${manual.jdbc.log}")
        assertEquals("Submitted 1 change (not committed yet)", status())
        assertTrue(fx(robot) { lines().any { it.contains("(not committed yet)") } })
    }

    @Test
    fun `inside a transaction of the user in Auto mode the savepoint is SQL text`(robot: FxRobot) {
        fake.pendingTransaction = true
        reloadsWith(fake, "zed")
        showRows(robot)
        editName(robot, 0, "zed")
        robot.interact { submitButton().fire() }
        robot.interact { }
        assertEquals("sql SAVEPOINT db_edit", fake.jdbc.log.first())
        assertTrue("autoCommit=false" !in fake.jdbc.log)
    }

    @Test
    fun `deletes that are committed at once ask first, with Cancel they do not run`(robot: FxRobot) {
        showRows(robot)
        val asked = mutableListOf<Pair<Int, String>>()
        var answer = false
        robot.interact {
            tab().view.gridEditing.confirmDelete = { n, table ->
                asked += n to table
                answer
            }
            selectCell(0, 1)
            button("Delete Rows").fire()
        }
        robot.interact { submitButton().fire() }
        assertEquals(listOf(1 to "\"users\""), asked)
        assertTrue(fake.changes.isEmpty(), "Cancel: nothing runs")
        assertTrue(area.pendingEdits)
        answer = true
        reloadsWith(fake, "a")
        robot.interact { submitButton().fire() }
        robot.interact { }
        assertEquals(listOf(true), fake.changes)
        assertTrue(fake.jdbc.log.any { it.startsWith("execute DELETE") })
    }

    @Test
    fun `deletes inside a Manual transaction are not asked about`(robot: FxRobot) {
        val manual = EditingSession(config.copy(commitMode = CommitMode.MANUAL), autoCommit = false)
        manual.commitMode = CommitMode.MANUAL
        reloadsWith(manual, "a")
        robot.interact { entry.connected(manual) }
        showRows(robot)
        var asked = false
        robot.interact {
            tab().view.gridEditing.confirmDelete = { _, _ ->
                asked = true
                false
            }
            selectCell(0, 1)
            button("Delete Rows").fire()
        }
        robot.interact { submitButton().fire() }
        robot.interact { }
        assertFalse(asked)
        assertEquals(listOf(true), manual.changes)
    }
}
