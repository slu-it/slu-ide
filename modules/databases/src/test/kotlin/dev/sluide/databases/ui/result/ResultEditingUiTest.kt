package dev.sluide.databases.ui.result

import dev.sluide.databases.session.ColumnInfo
import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuButton
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.sql.Types
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Editing, export, the value viewer and transpose of the result grid in a scene. Their logic is in `DataEditsTest` and
 * [ResultLogicTest]; this is the wiring that needs controls. Submit: [ResultSubmitUiTest]. Needs a display, see the
 * `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ResultEditingUiTest : ResultEditingUiFixture() {
    @Start
    fun start(stage: Stage) = startArea(stage)

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
    fun `Open All as Scratch File CSV builds the text on the export executor and hands it to openScratch`(
        robot: FxRobot,
    ) {
        showRows(robot)
        val onFx = CopyOnWriteArrayList<Boolean>()
        robot.interact {
            area.exportExecutor =
                Executor { task ->
                    Thread {
                        onFx += Platform.isFxApplicationThread()
                        task.run()
                    }.start()
                }
            val menu =
                tab()
                    .content
                    .lookupAll(".menu-button")
                    .filterIsInstance<MenuButton>()
                    .first()
            val openAll = menu.items.first { it.text == "Open All as Scratch File" } as Menu
            openAll.items.first { it.text == "CSV" }.fire()
        }
        waitUntil(robot) { scratch.isNotEmpty() }
        assertEquals(listOf(false), onFx.toList(), "built once on the executor (the rows were cached), off FX")
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
    fun `the value viewer is read-only on a deleted row and while a submit runs`(robot: FxRobot) {
        showRows(robot)
        val requests = mutableListOf<ValueRequest>()
        robot.interact {
            tab().view.gridEditing.showValue = {
                requests += it
                "x"
            }
            tab().view.gridEditing.confirmDelete = { _, _ -> true }
            selectCell(1, 1)
            button("Delete Rows").fire()
        }
        robot.interact {
            selectCell(1, 1)
            key(KeyCode.ENTER, shift = true)
        }
        fake.hold = true
        robot.interact { submitButton().fire() }
        robot.interact {
            selectCell(0, 1)
            key(KeyCode.ENTER, shift = true)
        }
        assertEquals(listOf(ValueRequest("name", "b", false), ValueRequest("name", "a", false)), requests)
        assertEquals(listOf<Any?>("a", "b"), fx(robot) { grid().items.map { it[1] } })
        reloadsWith(fake, "a")
        robot.interact { fake.release() }
        robot.interact { }
    }

    @Test
    fun `a value applied after other rows were shown is dropped with a note`(robot: FxRobot) {
        showRows(robot)
        robot.interact {
            tab().view.gridEditing.showValue = {
                // What the dialog's nested event loop lets through: the next page arrives meanwhile.
                pagingButton(2).fire()
                val key = Any()
                Platform.runLater { Platform.exitNestedEventLoop(key, null) }
                Platform.enterNestedEventLoop(key)
                "applied"
            }
            selectCell(0, 1)
            key(KeyCode.ENTER, shift = true)
        }
        assertEquals(listOf<Any?>(3, "c"), fx(robot) { grid().items[0] })
        assertFalse(area.pendingEdits)
        assertEquals("The result changed; the value was not applied", status())
    }
}
