package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.result.CellFormat
import dev.sluide.databases.session.DbSessionClosedException
import dev.sluide.databases.session.StatementOutcome
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.scene.control.Tab
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Submit of the edits of the result grid in a scene: the statements, the delete question, the lock while it runs, the
 * reload after it. Its logic is in `EditSubmitterTest`, `SubmitPlanTest` and `DataEditsTest`; this is the wiring
 * that needs controls. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ResultSubmitUiTest : ResultEditingUiFixture() {
    @Start
    fun start(stage: Stage) = startArea(stage)

    @Test
    fun `editing a cell and Submit run the statements on the session and log them`(robot: FxRobot) {
        showRows(robot)
        editName(robot, 0, "zed")
        assertEquals("zed", fx(robot) { grid().items[0][1] })
        assertTrue(area.pendingEdits)
        assertTrue(fx(robot) { grid().lookupAll(".db-cell-changed").isNotEmpty() })

        reloadsWith(fake, "zed")
        fake.statementTimeoutSeconds = 9
        robot.interact { key(KeyCode.ENTER, shortcut = true) }
        robot.interact { }
        assertEquals(listOf(true), fake.changes)
        assertTrue("timeout 9" in fake.jdbc.log, "the statement timeout: ${fake.jdbc.log}")
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
        var asked = false
        area.confirmDiscard = {
            asked = true
            true
        }
        val close = Event(Tab.TAB_CLOSE_REQUEST_EVENT)
        robot.interact { tab().onCloseRequest.handle(close) }
        assertTrue(close.isConsumed, "the tab stays while its submit runs")
        assertFalse(asked)
        assertEquals("A submit is running; close the tab when it has ended", status())
        fake.hold = false
        reloadsWith(fake, "zed")
        robot.interact { fake.release() }
        robot.interact { }
        assertFalse(fx(robot) { button("Add Row").isDisable })
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
        robot.interact { button("Add Row").fire() }
        robot.interact { submitButton().fire() }
        robot.interact { }
        assertEquals(
            "Submitted 1 change (not committed yet); reloaded to show the values the database filled in",
            status(),
        )
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

    @Test
    fun `a parse error or an unchanged text leaves no edits, so edits after a client sort key on the right row`(
        robot: FxRobot,
    ) {
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
        robot.interact { grid().edit(-1, null) } // Escape
        editName(robot, 0, "a")
        assertFalse(area.pendingEdits)
        assertTrue(fx(robot) { grid().lookupAll(".db-cell-changed").isEmpty() }, "unchanged text marks nothing")

        robot.interact { click(header(0)) }
        robot.interact { click(header(0)) }
        assertEquals(listOf<Any?>(2, 1), fx(robot) { ids() }, "sorted descending on the client")
        editName(robot, 0, "zed")
        reloadsWith(fake, "a")
        robot.interact { submitButton().fire() }
        robot.interact { }
        val log = fake.jdbc.log.toList()
        assertTrue("setObject 1=zed type=1111" in log && "setObject 2=2" in log, "WHERE id = 2: $log")
    }

    @Test
    fun `a submit that fails because the session closed releases the lock and keeps the edits`(robot: FxRobot) {
        showRows(robot)
        editName(robot, 0, "zed")
        fake.failure = DbSessionClosedException()
        robot.interact { submitButton().fire() }
        robot.interact { }
        assertEquals("The database session is closed", status())
        assertTrue(area.pendingEdits)
        assertFalse(fx(robot) { button("Add Row").isDisable || submitButton().isDisable })
    }

    @Test
    fun `the reload after a submit shows the same page again with its client sort`(robot: FxRobot) {
        fun outcome(fourth: String): StatementOutcome.Rows {
            val data = listOf(listOf<Any?>(1, "a"), listOf<Any?>(2, "b"), listOf<Any?>(3, "c"), listOf<Any?>(4, fourth))
            val cursor = FakeCursor(columns, data)
            return StatementOutcome.Rows("select * from users", 5, columns, cursor, cursor.firstPage())
        }
        robot.interact { area.show(outcome("d")) }
        robot.interact { }
        robot.interact { pagingButton(2).fire() }
        robot.interact { }
        robot.interact { click(header(0)) }
        robot.interact { click(header(0)) }
        assertEquals(listOf<Any?>(4, 3), fx(robot) { ids() })
        editName(robot, 0, "zed")
        fake.reloaded = { outcome("zed") }
        robot.interact { submitButton().fire() }
        waitUntil(robot) { status() == "Submitted 1 change" && ids() == listOf(4, 3) }
        assertTrue("setObject 2=4" in fake.jdbc.log, "${fake.jdbc.log}")
        assertEquals(listOf<Any?>("zed", "c"), fx(robot) { grid().items.map { it[1] } })
        assertEquals("id ▼", fx(robot) { header(0).text })

        // Deleting the rows of the last page: the page is gone, the last one is shown.
        robot.interact {
            tab().view.gridEditing.confirmDelete = { _, _ -> true }
            grid().selectionModel.selectAll()
            button("Delete Rows").fire()
        }
        fake.reloaded = { sql ->
            val cursor = FakeCursor(columns, listOf(listOf<Any?>(1, "a"), listOf<Any?>(2, "b")))
            StatementOutcome.Rows(sql, 5, columns, cursor, cursor.firstPage())
        }
        robot.interact { submitButton().fire() }
        waitUntil(robot) { ids() == listOf(2, 1) }
        assertEquals("Submitted 2 changes", fx(robot) { status() })
    }

    @Test
    fun `Submit takes the text of an open cell editor first and does not submit one that does not parse`(
        robot: FxRobot,
    ) {
        showRows(robot, numbers)
        robot.interact {
            selectCell(1, 0)
            key(KeyCode.ENTER)
        }
        robot.interact { editors().first().text = "abc" }
        assertFalse(fx(robot) { submitButton().isDisable }, "an open editor can be submitted")
        robot.interact { submitButton().fire() }
        assertTrue(fake.changes.isEmpty(), "nothing is submitted")
        assertEquals(1, fx(robot) { editors().count() }, "the editor stays open")
        val parseError = runCatching { CellFormat.parse("abc", numbers[0]) }.exceptionOrNull()?.message
        assertEquals(parseError, status())

        reloadsWith(fake, "a")
        robot.interact {
            val field = editors().first()
            field.text = "7"
            Event.fireEvent(
                field,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, !mac, false, mac),
            )
        }
        robot.interact { }
        assertEquals(listOf(true), fake.changes)
        val log = fake.jdbc.log.toList()
        assertTrue("setObject 1=7" in log && "setObject 2=2" in log, "UPDATE id = 7 WHERE id = 2: $log")
    }

    @Test
    fun `deletes are refused when the transaction of the user ended before the submit ran`(robot: FxRobot) {
        fake.pendingTransaction = true
        showRows(robot)
        var asked = false
        robot.interact {
            tab().view.gridEditing.confirmDelete = { _, _ ->
                asked = true
                true
            }
            selectCell(0, 1)
            button("Delete Rows").fire()
        }
        fake.hold = true
        robot.interact { submitButton().fire() }
        assertFalse(asked, "inside the transaction the deletes are not asked about")
        fake.pendingTransaction = false
        robot.interact { fake.release() }
        robot.interact { }
        assertEquals("The transaction ended; submit again", status())
        assertTrue(fake.jdbc.log.isEmpty(), "nothing ran: ${fake.jdbc.log}")
        assertTrue(area.pendingEdits)
    }
}
