package dev.sluide.httpclient.envedit

import javafx.scene.control.TextField
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Editing in the environment table with keys and mouse, and what a refresh keeps. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class EnvironmentTableEditingUiTest : EnvironmentFileViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** Focuses the cell of [path] in [env] and presses [code] in the table. */
    private fun EnvironmentFileView.pressOn(
        robot: FxRobot,
        path: ValuePath,
        env: String,
        code: KeyCode,
    ): EnvValueCell {
        val cell = cell(robot, path, env)
        robot.interact {
            val row = form.tree.getRow(item(path))
            form.tree.selectionModel.select(row, cell.tableColumn)
            form.tree.focusModel.focus(row, cell.tableColumn)
            form.tree.requestFocus()
            press(form.tree, code)
        }
        WaitForAsyncUtils.waitForFxEvents()
        return cell
    }

    @Test
    fun `Enter starts editing and Escape cancels it`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        val cell = view.pressOn(robot, path("host"), "dev", KeyCode.ENTER)
        assertTrue(cell.isEditing)
        val field = cell.graphic as TextField
        assertEquals("https://dev", field.text)
        robot.interact {
            field.text = "dropped"
            press(field, KeyCode.ESCAPE)
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(cell.isEditing)
        assertEquals(publicText, view.text)
        assertEquals("https://dev", view.shown(robot, path("host"), "dev"))
    }

    @Test
    fun `F2 starts editing and leaving the field commits it`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        val cell = view.pressOn(robot, path("host"), "prod", KeyCode.F2)
        val field = cell.graphic as TextField
        assertTrue(field.isFocused)
        robot.interact { field.text = "https://left" }
        robot.interact { view.showValues.requestFocus() }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue("\"host\": \"https://left\"" in view.text, view.text)
        assertNull(view.form.tree.editingCell)
    }

    @Test
    fun `an invalid number is dropped when the field loses the focus`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        val cell = view.pressOn(robot, path("port"), "dev", KeyCode.ENTER)
        val field = cell.graphic as TextField
        robot.interact { field.text = "80 80" }
        robot.interact { view.showValues.requestFocus() }
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(cell.isEditing)
        assertEquals(publicText, view.text)
    }

    @Test
    fun `a double-click flips a yes or no`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)
        val cell = view.cell(robot, path("debug"), "dev")

        robot.interact {
            click(cell, 1)
            click(cell, 2)
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue("\"debug\": false" in view.text, view.text)
    }

    @Test
    fun `a refresh keeps collapsed rows, the selection and the scroll position`(robot: FxRobot) {
        val variables = (0 until 60).joinToString(",\n") { "    \"v$it\": \"$it\"" }
        val view = open(robot, "{\n  \"dev\": {\n    \"db\": {\"user\": \"u\"},\n$variables\n  }\n}\n")
        val tree = view.form.tree
        val flow = { tree.lookup(".virtual-flow") as VirtualFlow<*> }
        robot.interact {
            view.item(path("db")).isExpanded = false
            tree.selectionModel.select(tree.getRow(view.item(path("v30"))), tree.columns[2])
            tree.scrollTo(25)
        }
        WaitForAsyncUtils.waitForFxEvents()
        val top = flow().firstVisibleCell.index

        robot.interact { view.form.setValue("dev", path("v40"), EnvJson.nodes.stringNode("changed")) }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue("\"v40\": \"changed\"" in view.text)
        assertFalse(view.item(path("db")).isExpanded)
        val selected = tree.selectionModel.selectedCells.single()
        assertEquals(path("v30"), (selected.treeItem.value as EnvEntry.Item).row.path)
        assertEquals("dev", selected.tableColumn.text)
        assertEquals(top, flow().firstVisibleCell.index)
    }

    @Test
    fun `the environments share the width the name and type leave`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)
        robot.interact { view.modeButtons[2].fire() }
        WaitForAsyncUtils.waitForFxEvents()
        val tree = view.form.tree
        val (name, type, dev, prod) = tree.columns.toList()

        assertEquals(250.0, name.width, 1.0)
        assertEquals(dev.width, prod.width, 1.0)
        assertEquals(tree.width - 20, name.width + type.width + dev.width + prod.width, 2.0)
    }
}
