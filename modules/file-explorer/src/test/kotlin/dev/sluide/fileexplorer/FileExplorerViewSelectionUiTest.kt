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
class FileExplorerViewSelectionUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private val opened = mutableListOf<Path>()
    private val deleted = mutableListOf<Pair<Path, List<Path>>>()
    private val questions = mutableListOf<DeleteQuestion>()
    private val errors = mutableListOf<String>()

    @Start
    fun start(stage: Stage) {
        val fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        view.onOpenFile = { opened.add(it) }
        view.onDeleted = { path, unsaved -> deleted.add(path to unsaved) }
        view.confirmDelete = {
            questions += it
            true
        }
        view.showError = { errors += it }
        stage.scene = Scene(view, 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    private fun renderedNames(robot: FxRobot): List<String> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .sortedBy { it.index }
            .mapNotNull { it.text }

    private fun cellNamed(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .first { it.text == name }

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
        WaitForAsyncUtils.waitForFxEvents()
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
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun menuItemFor(
        robot: FxRobot,
        node: Node,
        text: String,
        keyboard: Boolean = false,
    ): MenuItem {
        robot.interact {
            Event.fireEvent(
                node,
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, keyboard, null),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
        val item = view.contextMenu.items.single { it.text == text }
        robot.interact { view.contextMenu.hide() }
        return item
    }

    private fun pressKey(
        code: KeyCode,
        shortcut: Boolean = false,
    ) = Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, false))

    private fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            var failure: AssertionError? = null
            robot.interact {
                try {
                    assertion()
                } catch (e: AssertionError) {
                    failure = e
                }
            }
            val failed = failure ?: return
            if (System.nanoTime() > deadline) throw failed
            Thread.sleep(50)
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
    fun `the items are enabled by the entries the menu is for`(robot: FxRobot) {
        layout()
        open(robot)
        val file = cellNamed(robot, "a.txt")
        val folder = cellNamed(robot, "dir")
        val top = cellNamed(robot, project.fileName.toString())

        // A file: Open, Rename and Delete, but nothing is created beside it.
        assertEquals(
            listOf(false, true, true, false, false),
            listOf(
                "Open",
                "New File…",
                "New Folder…",
                "Rename…",
                "Delete…",
            ).map { menuItemFor(robot, file, it).isDisable },
        )
        // A folder.
        assertEquals(
            listOf(true, false, false, false, false),
            listOf(
                "Open",
                "New File…",
                "New Folder…",
                "Rename…",
                "Delete…",
            ).map { menuItemFor(robot, folder, it).isDisable },
        )
        // A top-level folder: creating only.
        assertEquals(
            listOf(true, false, false, true, true),
            listOf(
                "Open",
                "New File…",
                "New Folder…",
                "Rename…",
                "Delete…",
            ).map { menuItemFor(robot, top, it).isDisable },
        )
        // The empty area stands for the project folder.
        assertEquals(
            listOf(true, false, false, true, true),
            listOf(
                "Open",
                "New File…",
                "New Folder…",
                "Rename…",
                "Delete…",
            ).map { menuItemFor(robot, view, it).isDisable },
        )
        // Several entries, the keyboard opening the menu for the selection.
        select(robot, "a.txt", "dir")
        assertEquals(
            listOf(false, true, true, true, false),
            listOf("Open", "New File…", "New Folder…", "Rename…", "Delete…").map {
                menuItemFor(robot, view, it, keyboard = true).isDisable
            },
        )
        // One of them top-level: no Delete.
        select(robot, "a.txt", project.fileName.toString())
        assertTrue(menuItemFor(robot, view, "Delete…", keyboard = true).isDisable)
        assertFalse(menuItemFor(robot, view, "Refresh", keyboard = true).isDisable)
    }

    @Test
    fun `folder actions follow New File and are disabled on a file`(robot: FxRobot) {
        layout()
        view.folderActions = listOf(FolderAction("Do it") { FolderActionResult(null) })
        open(robot)

        assertTrue(menuItemFor(robot, cellNamed(robot, "a.txt"), "Do it").isDisable)
        assertFalse(menuItemFor(robot, cellNamed(robot, "dir"), "Do it").isDisable)
        assertFalse(menuItemFor(robot, view, "Do it").isDisable)
        select(robot, "dir", "a.txt")
        assertTrue(menuItemFor(robot, view, "Do it", keyboard = true).isDisable)
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
        view.unsavedUnder = { if (it.fileName.toString() == "a.txt") listOf(it) else emptyList() }
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
                    1,
                ),
            ),
            questions,
        )
        assertEquals(listOf(files[0]), deleted[1].second)
        assertFalse(project.resolve("dir").exists())
        robot.interact { assertEquals(listOf("b.txt"), selectedNames()) }
    }

    @Test
    fun `Delete does nothing when a selected entry is top-level`(robot: FxRobot) {
        layout()
        open(robot)
        select(robot, "a.txt", project.fileName.toString())

        robot.interact { pressKey(KeyCode.DELETE) }
        robot.interact { pressKey(KeyCode.BACK_SPACE, shortcut = true) }

        assertEquals(emptyList(), questions)
    }
}
