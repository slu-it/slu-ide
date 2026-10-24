package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.DeleteQuestion
import dev.sluide.fileexplorer.actions.EntryKind
import dev.sluide.fileexplorer.actions.deleteQuestion
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Multi-selection, the context menu rules and multi-delete of the explorer. Needs a display, see `uiTest`. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewSelectionUiTest : FileExplorerViewUiFixture() {
    private val opened = mutableListOf<Path>()
    private val deleted = mutableListOf<Pair<Path, List<Path>>>()
    private val questions = mutableListOf<DeleteQuestion>()
    private val errors = mutableListOf<String>()

    @Start
    fun start(stage: Stage) =
        startView(stage, configure = {
            it.onOpenFile = { path -> opened.add(path) }
            it.onDeleted = { path, unsaved -> deleted.add(path to unsaved) }
            it.confirmDelete = { question ->
                questions += question
                true
            }
            it.showError = { message -> errors += message }
        })

    private fun open(
        robot: FxRobot,
        expand: Boolean = true,
    ) {
        robot.interact {
            view.open(project)
            if (expand) {
                view.projectNode.children
                    .filterIsInstance<FileTreeItem>()
                    .forEach { it.isExpanded = true }
            }
        }
    }

    private fun item(name: String): FileTreeItem =
        generateSequence(listOf<TreeItem<Path>>(view.projectNode)) { level ->
            level.flatMap { it.children }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<FileTreeItem>().first { it.value.fileName.toString() == name }

    private fun select(
        robot: FxRobot,
        vararg names: String,
    ) {
        robot.interact {
            view.selectionModel.clearSelection()
            names.forEach { view.selectionModel.select(item(it)) }
        }
    }

    private fun selectedNames() =
        view.selectionModel.selectedItems
            .filterNotNull()
            .map { it.value.fileName.toString() }

    /**
     * A click on the row of [name] as synthetic events (no OS input), with Shift or the shortcut key held; Control and
     * Meta are both down so the shortcut key is down on every platform.
     */
    private fun click(
        robot: FxRobot,
        name: String,
        button: MouseButton = MouseButton.PRIMARY,
        shift: Boolean = false,
        shortcut: Boolean = false,
    ) {
        val cell = cellNamed(robot, name)
        robot.interact {
            val scene = cell.localToScene(5.0, 5.0)
            val screen = cell.localToScreen(5.0, 5.0)
            val secondary = button == MouseButton.SECONDARY
            for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                val down = type == MouseEvent.MOUSE_PRESSED
                Event.fireEvent(
                    cell,
                    MouseEvent(
                        cell,
                        cell,
                        type,
                        scene.x,
                        scene.y,
                        screen.x,
                        screen.y,
                        button,
                        1,
                        shift,
                        shortcut,
                        false,
                        shortcut,
                        down && !secondary,
                        false,
                        down && secondary,
                        false,
                        secondary && down,
                        true,
                        PickResult(cell, scene.x, scene.y),
                    ),
                )
            }
        }
    }

    private fun layout(): List<Path> {
        project.resolve("dir/inner.txt").createParentDirectories().createFile()
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        project.resolve("c.txt").createFile()
        return listOf("a.txt", "b.txt", "c.txt").map { project.resolve(it) }
    }

    @Test
    fun `the explorer allows selecting several entries`(robot: FxRobot) {
        layout()
        open(robot)
        assertEquals(SelectionMode.MULTIPLE, view.selectionModel.selectionMode)

        click(robot, "a.txt")
        click(robot, "c.txt", shift = true)
        robot.interact { assertEquals(listOf("a.txt", "b.txt", "c.txt"), selectedNames()) }

        click(robot, "a.txt")
        click(robot, "c.txt", shortcut = true)
        robot.interact { assertEquals(listOf("a.txt", "c.txt"), selectedNames()) }
    }

    @Test
    fun `right-click on a selected row keeps the selection and the menu applies to all of it`(robot: FxRobot) {
        layout()
        open(robot)
        select(robot, "a.txt", "c.txt")

        click(robot, "c.txt", button = MouseButton.SECONDARY)
        robot.interact { assertEquals(listOf("a.txt", "c.txt"), selectedNames()) }
        assertTrue(menuItemFor(robot, cellNamed(robot, "c.txt"), "Rename…").isDisable)
        val delete = menuItemFor(robot, cellNamed(robot, "c.txt"), "Delete…")
        assertFalse(delete.isDisable)
        robot.interact { delete.fire() }

        eventually(robot) { assertEquals(2, deleted.size) }
        assertEquals(listOf(project.resolve("a.txt"), project.resolve("c.txt")), deleted.map { it.first })
        assertTrue(project.resolve("b.txt").exists())
    }

    @Test
    fun `right-click on a row outside the selection is for that row alone`(robot: FxRobot) {
        layout()
        open(robot)
        select(robot, "a.txt", "c.txt")

        click(robot, "b.txt", button = MouseButton.SECONDARY)
        robot.interact { assertEquals(listOf("b.txt"), selectedNames()) }
        assertFalse(menuItemFor(robot, cellNamed(robot, "b.txt"), "Rename…").isDisable)
    }

    @Test
    fun `Open opens every selected file in row order and skips folders`(robot: FxRobot) {
        layout()
        open(robot)
        select(robot, "c.txt", "dir", "a.txt")

        val open = menuItemFor(robot, view, "Open", keyboard = true)
        assertFalse(open.isDisable)
        robot.interact { open.fire() }

        assertEquals(listOf(project.resolve("a.txt"), project.resolve("c.txt")), opened)
    }

    @Test
    fun `Enter opens all selected files, F2 renames only a single entry`(robot: FxRobot) {
        layout()
        open(robot)
        val asked = mutableListOf<String>()
        view.askName = { _, initial, _ ->
            asked += initial
            null
        }
        select(robot, "a.txt", "b.txt")

        robot.interact { pressKey(KeyCode.ENTER) }
        robot.interact { pressKey(KeyCode.F2) }
        assertEquals(listOf(project.resolve("a.txt"), project.resolve("b.txt")), opened)
        assertEquals(emptyList(), asked)

        select(robot, "dir", "b.txt")
        robot.interact { pressKey(KeyCode.ENTER) }
        assertEquals(3, opened.size)

        select(robot, "b.txt")
        robot.interact { pressKey(KeyCode.F2) }
        assertEquals(listOf("b.txt"), asked)
    }

    @Test
    fun `Delete deletes all selected entries after one question and selects the next entry`(robot: FxRobot) {
        val files = layout()
        open(robot)
        select(robot, "dir", "a.txt")

        robot.interact { pressKey(KeyCode.DELETE) }

        eventually(robot) {
            assertEquals(listOf("dir", "a.txt").map { project.resolve(it) }, deleted.map { it.first })
            assertEquals(listOf(project.fileName.toString(), "b.txt", "c.txt"), renderedNames(robot))
        }
        assertEquals(
            listOf(
                deleteQuestion(
                    listOf(project.resolve("dir") to EntryKind.FOLDER, files[0] to EntryKind.FILE),
                    0,
                ),
            ),
            questions,
        )
        assertFalse(project.resolve("dir").exists())
        robot.interact { assertEquals(listOf("b.txt"), selectedNames()) }
    }
}
