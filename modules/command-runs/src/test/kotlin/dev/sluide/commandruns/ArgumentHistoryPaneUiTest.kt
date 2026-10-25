package dev.sluide.commandruns

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.SettingsStore
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The history section of the run pane. A UI test: what it checks is focus, key and mouse routing, visibility and the
 * bindings to the global expanded state, which need a scene; the history's logic is in [ArgumentHistoriesTest].
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ArgumentHistoryPaneUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private lateinit var pane: RunPane

    private val histories by lazy {
        ArgumentHistories(
            ProjectDataStore(dir.resolve("projects"), dir.resolve("outside.json"), emptyList()),
            SettingsStore(dir.resolve("settings.properties")),
        )
    }

    private val file by lazy { dir.resolve("run.sh") }

    private fun history() = ArgumentHistory(histories, { file }, { dir })

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.show()
    }

    /**
     * Shows a run pane with a history of [entries] (the first ends on top), stored before the pane's history is
     * attached; returns that history.
     */
    private fun show(vararg entries: String): ArgumentHistory {
        entries.reversed().forEach { histories.record(file, dir, it) }
        pane = RunPane("", "")
        val history = history()
        pane.history.attach(history)
        stage.scene = Scene(pane.node, 400.0, 400.0)
        return history
    }

    private fun press(
        node: Node,
        code: KeyCode,
        shift: Boolean = false,
    ) = Event.fireEvent(node, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false))

    /** Presses, releases and clicks the primary button on [node]. */
    private fun click(node: Node) {
        fireMouse(node, MouseEvent.MOUSE_PRESSED, down = true)
        fireMouse(node, MouseEvent.MOUSE_RELEASED, down = false)
        fireMouse(node, MouseEvent.MOUSE_CLICKED, down = false)
    }

    private fun fireMouse(
        node: Node,
        type: EventType<MouseEvent>,
        down: Boolean,
    ) = Event.fireEvent(
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
            down,
            false,
            false,
            false,
            false,
            true,
            null,
        ),
    )

    private fun cell(
        robot: FxRobot,
        index: Int,
    ): ListCell<*> =
        robot.lookup(".run-history-list .list-cell").queryAllAs(ListCell::class.java).single {
            it.index ==
                index
        }

    private fun remove(cell: ListCell<*>): Button = cell.lookup(".run-history-remove") as Button

    private val focusOwner: Node? get() = stage.scene.focusOwner

    @Test
    fun `the section shows the entries stored before, and hides while there are none`(robot: FxRobot) {
        robot.interact {
            show("-x", "-y")
            assertTrue(pane.history.node.isVisible)
            assertEquals(listOf("-x", "-y"), pane.history.list.items)

            val empty = RunPane("", "")
            empty.history.attach(ArgumentHistory(histories, { dir.resolve("other.sh") }, { dir }))
            assertFalse(empty.history.node.isVisible)
        }
    }

    @Test
    fun `the section shows after the first record and hides when the last entry is removed`(robot: FxRobot) {
        robot.interact {
            val history = show()
            assertFalse(pane.history.node.isVisible)

            history.record("-x")
            assertTrue(pane.history.node.isVisible)

            history.remove("-x")
            assertFalse(pane.history.node.isVisible)
        }
    }

    @Test
    fun `a click on a row puts it into the arguments without running`(robot: FxRobot) {
        var actions = 0
        robot.interact {
            show("-x test", "build")
            pane.arguments.setOnAction { actions++ }
        }

        robot.interact {
            click(cell(robot, 1))

            assertEquals("build", pane.arguments.text)
            assertEquals("build".length, pane.arguments.caretPosition)
            assertSame(pane.arguments, focusOwner)
            assertEquals(0, actions)
        }
    }

    @Test
    fun `the remove button of the selected row removes its entry without filling the arguments`(robot: FxRobot) {
        lateinit var history: ArgumentHistory
        robot.interact {
            history = show("-x", "-y")
            pane.history.list.selectionModel
                .select(0)
        }
        robot.interact {
            assertTrue(remove(cell(robot, 0)).isVisible)
            assertFalse(remove(cell(robot, 1)).isVisible)
            // A click on ✕ never reaches its row; checked alone, as the removal below may take ✕ out of the row first.
            fireMouse(remove(cell(robot, 0)), MouseEvent.MOUSE_CLICKED, down = false)
            assertEquals("", pane.arguments.text)
        }

        robot.interact {
            click(remove(cell(robot, 0)))

            assertEquals(listOf("-y"), history.entries)
            assertEquals(listOf("-y"), histories.entries(file, dir))
            assertEquals("", pane.arguments.text)
        }
    }

    @Test
    fun `Down in the arguments goes into the list while it shows expanded`(robot: FxRobot) {
        robot.interact {
            show("-x", "-y")
            pane.arguments.requestFocus()
            press(pane.arguments, KeyCode.DOWN, shift = true)
            assertSame(pane.arguments, focusOwner)

            press(pane.arguments, KeyCode.DOWN)
            assertSame(pane.history.list, focusOwner)
            assertEquals(0, pane.history.list.selectionModel.selectedIndex)
            // Choosing a row is not taking it.
            assertEquals("", pane.arguments.text)
        }

        robot.interact {
            pane.history.header.fire()
            pane.history.list.selectionModel
                .clearSelection()
            pane.arguments.requestFocus()
            press(pane.arguments, KeyCode.DOWN)

            assertSame(pane.arguments, focusOwner)
            assertEquals(-1, pane.history.list.selectionModel.selectedIndex)
        }
    }

    @Test
    fun `Down in the arguments does nothing while the history is empty`(robot: FxRobot) {
        robot.interact {
            show()
            pane.arguments.requestFocus()
            pane.arguments.text = "-x"
            pane.arguments.positionCaret(0)
            press(pane.arguments, KeyCode.DOWN)

            assertSame(pane.arguments, focusOwner)
            // The field's own Down: the caret goes to the end.
            assertEquals(2, pane.arguments.caretPosition)
        }
    }

    @Test
    fun `Enter in the list puts the entry into the arguments, Escape goes back unchanged`(robot: FxRobot) {
        robot.interact {
            show("-x", "-y")
            pane.history.list.requestFocus()
            pane.history.list.selectionModel
                .select(1)
            press(pane.history.list, KeyCode.ENTER, shift = true)
            assertEquals("", pane.arguments.text)

            press(pane.history.list, KeyCode.ENTER)
            assertEquals("-y", pane.arguments.text)
            assertSame(pane.arguments, focusOwner)
        }

        robot.interact {
            pane.history.list.requestFocus()
            pane.history.list.selectionModel
                .select(0)
            press(pane.history.list, KeyCode.ESCAPE)

            assertEquals("-y", pane.arguments.text)
            assertSame(pane.arguments, focusOwner)
        }
    }

    @Test
    fun `collapsing hides the list in every pane`(robot: FxRobot) {
        val other = ArgumentHistoryPane(TextField())
        robot.interact {
            show("-x")
            other.attach(history())
            assertTrue(pane.history.list.isVisible)

            pane.history.header.fire()

            assertFalse(pane.history.list.isVisible)
            assertEquals("▸ History", pane.history.header.text)
            assertFalse(other.list.isVisible)
        }
    }

    @Test
    fun `the run pane shows the history between the arguments and the status line`(robot: FxRobot) {
        robot.interact {
            show("-x")

            val rows = (pane.node.top as VBox).children
            assertEquals(1, rows.indexOf(pane.history.node))
            assertTrue(pane.arguments in (rows[0] as HBox).children)
            assertTrue(pane.status in (rows[2] as HBox).children)
        }
    }
}
