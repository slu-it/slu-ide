package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import dev.sluide.settings.SettingsStore
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Renders the real view in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private lateinit var fontSettings: FontSettings
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)

    @Start
    fun start(stage: Stage) {
        fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        stage.scene = Scene(view, 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    private fun renderedNames(robot: FxRobot): List<String> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .sortedBy { it.index } // lookups return cells in no particular order
            .mapNotNull { it.text }

    @Test
    fun `renders hidden files and folders, folders first`(robot: FxRobot) {
        project.resolve(".hidden-dir").createDirectories()
        project.resolve("sub").createDirectories()
        project.resolve(".hidden-file").createFile()
        project.resolve("visible.txt").createFile()

        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            listOf(project.name, ".hidden-dir", "sub", ".hidden-file", "visible.txt"),
            renderedNames(robot),
        )
    }

    @Test
    fun `expanding a folder renders its children`(robot: FxRobot) {
        project.resolve("sub/deeper").createDirectories()
        project.resolve("sub/inner.txt").createFile()
        robot.interact { view.open(project) }

        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "sub", "deeper", "inner.txt"), renderedNames(robot))
    }

    @Test
    fun `opening null clears the view`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        robot.interact { view.open(project) }

        robot.interact { view.open(null) }
        WaitForAsyncUtils.waitForFxEvents()

        assertNull(view.root)
        assertEquals(emptyList(), renderedNames(robot))
    }

    @Test
    fun `requests opening files but not folders`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val folder = project.resolve("sub").createDirectories()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }

        robot.interact {
            assertTrue(view.requestOpen(file))
            assertFalse(view.requestOpen(folder))
        }

        assertEquals(listOf(file), requested)
    }

    @Test
    fun `Enter on a selected file requests opening it`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }

        robot.interact {
            view.selectionModel.select(view.root.children.single())
            pressKey(KeyCode.ENTER)
            view.selectionModel.select(view.root)
            pressKey(KeyCode.ENTER)
        }

        assertEquals(listOf(file), requested)
    }

    private fun pressKey(code: KeyCode) =
        Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))

    @Test
    fun `double-click on a file requests opening it, on a folder it does not`(robot: FxRobot) {
        val folder = project.resolve("sub").createDirectories()
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        val cells = robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).filter { it.item != null }
        robot.interact {
            doubleClick(cells.single { it.item == file })
            doubleClick(cells.single { it.item == folder })
        }

        assertEquals(listOf(file), requested)
    }

    private fun doubleClick(cell: TreeCell<*>) =
        Event.fireEvent(
            cell,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                2,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )

    @Test
    fun `files created and deleted on disk appear and disappear`(robot: FxRobot) {
        project.resolve("existing.txt").createFile()
        robot.interact { view.open(project) }

        val created = project.resolve("created.txt").createFile()
        eventually(robot) { assertEquals(listOf(project.name, "created.txt", "existing.txt"), renderedNames(robot)) }

        created.deleteExisting()
        eventually(robot) { assertEquals(listOf(project.name, "existing.txt"), renderedNames(robot)) }
    }

    @Test
    fun `changes inside an expanded subfolder appear and keep the expansion and selection`(robot: FxRobot) {
        val kept = project.resolve("sub/kept.txt").createParentDirectories().createFile()
        robot.interact { view.open(project) }
        robot.interact {
            val sub = view.root.children.single()
            sub.isExpanded = true
            view.selectionModel.select(sub.children.single())
        }

        // Inserted before the selected file, which moves down a row.
        project.resolve("sub/inner.txt").createFile()
        project.resolve("top.txt").createFile()

        eventually(robot) {
            assertEquals(listOf(project.name, "sub", "inner.txt", "kept.txt", "top.txt"), renderedNames(robot))
        }
        robot.interact { assertEquals(kept, view.selectionModel.selectedItem?.value) }
    }

    @Test
    fun `Refresh in the context menu re-reads loaded folders`(robot: FxRobot) {
        // Without working watches, only a manual refresh can show the new file.
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings)
            view.scene.root = manual
            manual.open(project)
        }
        project.resolve("late.txt").createFile()

        val refresh = manual.contextMenu.items.single { it.text == "Refresh" }
        robot.interact { refresh.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))
    }

    @Test
    fun `F5 refreshes`(robot: FxRobot) {
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings)
            view.scene.root = manual
            manual.open(project)
        }
        project.resolve("late.txt").createFile()

        robot.interact {
            Event.fireEvent(manual, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false))
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))
    }

    @Test
    fun `a deleted and recreated subfolder keeps being watched`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }

        sub.toFile().deleteRecursively()
        sub.createDirectories()
        sub.resolve("first.txt").createFile()
        eventually(robot) { assertEquals(listOf(project.name, "sub", "first.txt"), renderedNames(robot)) }

        sub.resolve("second.txt").createFile()
        eventually(robot) {
            assertEquals(listOf(project.name, "sub", "first.txt", "second.txt"), renderedNames(robot))
        }
    }

    /** Opens the context menu as a right-click on [node] (or the [keyboard]) does, and returns its item [text]. */
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

    /** Replaces [view] by one whose watches never report, so only the view itself can show changes. */
    private fun useUnwatchedView(robot: FxRobot) {
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        robot.interact {
            val replaced = view
            view = FileExplorerView(unwatched, fontSettings)
            replaced.scene.root = view
        }
    }

    @Test
    fun `New File creates a file beside the clicked file, selects and opens it at once`(robot: FxRobot) {
        project.resolve("sub/taken.txt").createParentDirectories().createFile()
        useUnwatchedView(robot)
        val opened = mutableListOf<Path>()
        view.onOpenFile = { opened.add(it) }
        val asked = mutableListOf<String?>()
        view.askName = { title, initial, validate ->
            asked += listOf(title, initial, validate("taken.txt"), validate("new.txt"))
            "new.txt"
        }
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()

        val newFile = menuItemFor(robot, cellNamed(robot, "taken.txt"), "New File…")
        robot.interact { newFile.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        val created = project.resolve("sub/new.txt")
        assertEquals(listOf("New File", "", "“taken.txt” already exists in this folder.", null), asked)
        assertTrue(Files.isRegularFile(created))
        assertEquals(listOf(project.name, "sub", "new.txt", "taken.txt"), renderedNames(robot))
        robot.interact { assertEquals(created, view.selectionModel.selectedItem?.value) }
        assertEquals(listOf(created), opened)
    }

    @Test
    fun `New Folder creates a folder in the clicked folder and expands it, or in the opened folder`(robot: FxRobot) {
        project.resolve("sub/inner.txt").createParentDirectories().createFile()
        useUnwatchedView(robot)
        val opened = mutableListOf<Path>()
        view.onOpenFile = { opened.add(it) }
        var name = "nested"
        view.askName = { _, _, _ -> name }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        val inSub = menuItemFor(robot, cellNamed(robot, "sub"), "New Folder…")
        robot.interact { inSub.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(Files.isDirectory(project.resolve("sub/nested")))
        assertEquals(listOf(project.name, "sub", "nested", "inner.txt"), renderedNames(robot))
        robot.interact {
            assertEquals(project.resolve("sub/nested"), view.selectionModel.selectedItem?.value)
            assertEquals(project.resolve("sub/nested"), view.focusModel.focusedItem?.value)
        }

        // The empty area below the entries stands for the opened folder.
        name = "top"
        val inProject = menuItemFor(robot, view, "New Folder…")
        robot.interact { inProject.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(Files.isDirectory(project.resolve("top")))
        assertEquals(listOf(project.name, "sub", "nested", "inner.txt", "top"), renderedNames(robot))
        robot.interact { assertEquals(project.resolve("top"), view.selectionModel.selectedItem?.value) }
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `the focus follows the new entry also when it is inserted before the focused one`(robot: FxRobot) {
        project.resolve("z.txt").createFile()
        view.askName = { _, _, _ -> "a" }
        robot.interact { view.open(project) }
        robot.interact { view.selectionModel.select(view.root.children.single()) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.create(null, folder = true) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            assertEquals(project.resolve("a"), view.selectionModel.selectedItem?.value)
            assertEquals(project.resolve("a"), view.focusModel.focusedItem?.value)
        }
    }

    @Test
    fun `nothing is created when the name is cancelled, an error is shown when creating fails`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        val errors = mutableListOf<String>()
        view.showError = { errors += it }
        view.askName = { _, _, _ -> null }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.create(null, folder = false) }
        assertEquals(listOf("sub"), project.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), errors)

        // The folder vanishes while the name is asked for.
        view.askName = { _, _, _ ->
            sub.deleteExisting()
            "late.txt"
        }
        robot.interact { view.create(view.root.children.single() as FileTreeItem, folder = false) }
        assertEquals(listOf("Could not create “late.txt”: ${sub.resolve("late.txt")} does not exist."), errors)
    }

    @Test
    fun `New File and New Folder are disabled without an opened folder`(robot: FxRobot) {
        val newFile = menuItemFor(robot, view, "New File…")
        val newFolder = view.contextMenu.items.single { it.text == "New Folder…" }

        assertTrue(newFile.isDisable)
        assertTrue(newFolder.isDisable)
    }

    @Test
    fun `Rename renames the clicked entry at once, selects it and reports the move`(robot: FxRobot) {
        val file = project.resolve("old.txt").apply { writeText("content") }
        project.resolve("other.txt").createFile()
        useUnwatchedView(robot)
        val moves = mutableListOf<Pair<Path, Path>>()
        view.onMoved = { from, to -> moves += from to to }
        val asked = mutableListOf<String?>()
        view.askName = { title, initial, validate ->
            asked += listOf(title, initial, validate("old.txt"), validate("other.txt"))
            "new.txt"
        }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        val rename = menuItemFor(robot, cellNamed(robot, "old.txt"), "Rename…")
        assertFalse(rename.isDisable)
        robot.interact { rename.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        val renamed = project.resolve("new.txt")
        assertEquals(listOf("Rename", "old.txt", null, "“other.txt” already exists in this folder."), asked)
        assertEquals("content", renamed.readText())
        assertEquals(listOf(project.name, "new.txt", "other.txt"), renderedNames(robot))
        robot.interact { assertEquals(renamed, view.selectionModel.selectedItem?.value) }
        assertEquals(listOf(file to renamed), moves)
    }

    @Test
    fun `a renamed folder keeps its content, stays expanded and is watched again`(robot: FxRobot) {
        project.resolve("sub/inner.txt").createParentDirectories().createFile()
        val moves = mutableListOf<Pair<Path, Path>>()
        view.onMoved = { from, to -> moves += from to to }
        view.askName = { _, _, _ -> "renamed" }
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.rename(view.root.children.single() as FileTreeItem) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.resolve("sub") to project.resolve("renamed")), moves)
        assertEquals(listOf(project.name, "renamed", "inner.txt"), renderedNames(robot))
        project.resolve("renamed/later.txt").createFile()
        eventually(
            robot,
        ) { assertEquals(listOf(project.name, "renamed", "inner.txt", "later.txt"), renderedNames(robot)) }
    }

    @Test
    fun `F2 renames the selected entry, but not the opened folder`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        val asked = mutableListOf<String>()
        view.askName = { _, initial, _ ->
            asked += initial
            null
        }
        robot.interact { view.open(project) }

        robot.interact {
            view.selectionModel.select(view.root.children.single())
            pressKey(KeyCode.F2)
            view.selectionModel.select(view.root)
            pressKey(KeyCode.F2)
        }

        assertEquals(listOf("a.txt"), asked)
        assertTrue(project.resolve("a.txt").exists())
    }

    @Test
    fun `Rename is disabled for the opened folder and the empty area`(robot: FxRobot) {
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(menuItemFor(robot, cellNamed(robot, project.name), "Rename…").isDisable)
        assertTrue(menuItemFor(robot, view, "Rename…").isDisable)
    }

    @Test
    fun `renaming to the same name changes nothing, a failed rename shows the error`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val moves = mutableListOf<Pair<Path, Path>>()
        view.onMoved = { from, to -> moves += from to to }
        val errors = mutableListOf<String>()
        view.showError = { errors += it }
        view.askName = { _, initial, _ -> initial }
        lateinit var item: FileTreeItem
        robot.interact {
            view.open(project)
            item = view.root.children.single() as FileTreeItem
        }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.rename(item) }
        assertTrue(file.exists())
        assertEquals(emptyList(), errors)

        view.askName = { _, _, _ ->
            file.deleteExisting()
            "b.txt"
        }
        robot.interact { view.rename(item) }
        assertEquals(listOf("Could not rename “a.txt”: $file does not exist."), errors)
        assertEquals(emptyList(), moves)
    }

    @Test
    fun `Delete asks, deletes a folder with its content at once, reports it and selects the next entry`(
        robot: FxRobot,
    ) {
        val sub = project.resolve("sub").createDirectories()
        sub.resolve("deeper/inner.txt").createParentDirectories().createFile()
        project.resolve("after.txt").createFile()
        useUnwatchedView(robot)
        val asked = mutableListOf<Path>()
        view.unsavedUnder = { path ->
            asked.add(path)
            listOf(path.resolve("deeper/inner.txt"), path.resolve("x.txt"))
        }
        val questions = mutableListOf<DeleteQuestion>()
        view.confirmDelete = {
            questions += it
            true
        }
        val deleted = mutableListOf<Path>()
        val lost = mutableListOf<Path>()
        view.onDeleted = { path, unsaved ->
            deleted.add(path)
            lost.addAll(unsaved)
        }
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .first()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()
        // As a right-click does.
        robot.interact { view.selectionModel.select(view.root.children.first()) }

        val delete = menuItemFor(robot, cellNamed(robot, "sub"), "Delete…")
        assertFalse(delete.isDisable)
        robot.interact { delete.fire() }

        // On a background thread; the view itself shows the result.
        eventually(robot) {
            assertEquals(listOf(sub), deleted)
            assertEquals(listOf(project.name, "after.txt"), renderedNames(robot))
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf(sub), asked)
        assertEquals(listOf(deleteQuestion(sub, EntryKind.FOLDER, 2)), questions)
        assertEquals(listOf(sub.resolve("deeper/inner.txt"), sub.resolve("x.txt")), lost, "the files counted as lost")
        assertFalse(sub.exists())
        robot.interact {
            assertEquals(project.resolve("after.txt"), view.selectionModel.selectedItem?.value)
            assertEquals(project.resolve("after.txt"), view.focusModel.focusedItem?.value)
        }
    }

    @Test
    fun `while an entry is deleted in the background, it cannot be deleted or renamed again`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        val pending = mutableListOf<Runnable>()
        view.runInBackground = { pending += it }
        var confirmations = 0
        view.confirmDelete = {
            confirmations++
            true
        }
        val deleted = mutableListOf<Path>()
        view.onDeleted = { path, _ -> deleted.add(path) }
        robot.interact { view.open(project) }
        robot.interact {
            view.selectionModel.select(view.root.children.first())
            pressKey(KeyCode.DELETE)
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(file.exists())
        robot.interact {
            // The next entry is selected at once.
            assertEquals(project.resolve("b.txt"), view.selectionModel.selectedItem?.value)
            view.selectionModel.select(view.root.children.first())
            pressKey(KeyCode.DELETE)
        }
        assertEquals(1, confirmations)
        assertTrue(menuItemFor(robot, cellNamed(robot, "a.txt"), "Delete…").isDisable)
        assertTrue(menuItemFor(robot, cellNamed(robot, "a.txt"), "Rename…").isDisable)

        pending.single().run()
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(file.exists())
        assertEquals(listOf(file), deleted)
        assertEquals(listOf(project.name, "b.txt"), renderedNames(robot))
    }

    @Test
    fun `nothing is deleted unless confirmed, a failed delete shows the error`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val errors = mutableListOf<String>()
        view.showError = { errors += it }
        val deleted = mutableListOf<Path>()
        view.onDeleted = { path, _ -> deleted.add(path) }
        view.confirmDelete = { false }
        lateinit var item: FileTreeItem
        robot.interact {
            view.open(project)
            item = view.root.children.single() as FileTreeItem
        }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.delete(item) }
        assertTrue(file.exists())

        view.confirmDelete = { true }
        // The file vanishes before it is deleted.
        view.runInBackground = { task ->
            file.deleteExisting()
            task.run()
        }
        robot.interact { view.delete(item) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Could not delete “a.txt”: $file does not exist."), errors)
        assertEquals(emptyList(), deleted)
    }

    @Test
    fun `an entry that changes its kind while asking is not deleted`(robot: FxRobot) {
        val entry = project.resolve("a").createFile()
        val errors = mutableListOf<String>()
        view.showError = { errors += it }
        val deleted = mutableListOf<Path>()
        view.onDeleted = { path, _ -> deleted.add(path) }
        // The question was about a file; now it is a folder.
        view.confirmDelete = {
            entry.deleteExisting()
            entry.resolve("inner.txt").createParentDirectories().createFile()
            true
        }
        lateinit var item: FileTreeItem
        robot.interact {
            view.open(project)
            item = view.root.children.single() as FileTreeItem
        }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.delete(item) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("“a” changed while asking and was not deleted."), errors)
        assertTrue(entry.resolve("inner.txt").exists())
        assertEquals(emptyList(), deleted)
    }

    @Test
    fun `the context menu opened by keyboard is for the selected entry`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        val asked = mutableListOf<String>()
        view.askName = { _, initial, _ ->
            asked += initial
            null
        }
        robot.interact { view.open(project) }
        robot.interact { view.selectionModel.select(view.root.children.single()) }
        WaitForAsyncUtils.waitForFxEvents()

        val rename = menuItemFor(robot, view, "Rename…", keyboard = true)
        assertFalse(rename.isDisable)
        robot.interact { rename.fire() }

        assertEquals(listOf("a.txt"), asked)
        // A right-click on the empty area is for the opened folder, whatever is selected.
        assertTrue(menuItemFor(robot, view, "Rename…").isDisable)
    }

    @Test
    fun `the Delete key and the shortcut key with Backspace delete the selected entry, but not the opened folder`(
        robot: FxRobot,
    ) {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        val asked = mutableListOf<String>()
        view.confirmDelete = {
            asked += it.header
            false
        }
        robot.interact { view.open(project) }

        robot.interact {
            view.selectionModel.select(view.root.children.first())
            pressKey(KeyCode.DELETE)
            view.selectionModel.select(view.root.children.last())
            pressKey(KeyCode.BACK_SPACE)
            // Control and Meta, so the shortcut key is down on every platform.
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, true, false, true))
            view.selectionModel.select(view.root)
            pressKey(KeyCode.DELETE)
        }

        assertEquals(listOf("Delete the file “a.txt”?", "Delete the file “b.txt”?"), asked)
    }

    @Test
    fun `Delete is disabled for the opened folder and the empty area`(robot: FxRobot) {
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(menuItemFor(robot, cellNamed(robot, project.name), "Delete…").isDisable)
        assertTrue(menuItemFor(robot, view, "Delete…").isDisable)
    }

    /** Repeats [assertion] on the FX thread until it passes; the JDK may poll for file changes on macOS. */
    private fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            try {
                var failure: AssertionError? = null
                robot.interact {
                    try {
                        assertion()
                    } catch (e: AssertionError) {
                        failure = e
                    }
                }
                failure?.let { throw it }
                return
            } catch (e: AssertionError) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun `the tree uses the explorer font and follows its changes`(robot: FxRobot) {
        project.resolve("README.md").createFile()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { fontSettings.apply(mapOf(FontArea.EXPLORER to FontSpec("Serif", 19.0, FontStyle.BOLD))) }
        WaitForAsyncUtils.waitForFxEvents()

        val cell =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .first { it.text == "README.md" }
        assertEquals(19.0, cell.font.size)
        assertEquals("Serif", cell.font.family)
        assertTrue("Bold" in cell.font.style, cell.font.style)
    }

    private fun cellNamed(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .first { it.text == name }

    private fun iconOf(
        robot: FxRobot,
        name: String,
    ): FontIcon = cellNamed(robot, name).graphic.lookup(".ikonli-font-icon") as FontIcon

    /** The style classes of the markers after [name], in the order they are shown. */
    private fun markersOf(
        robot: FxRobot,
        name: String,
    ): List<String> =
        cellNamed(robot, name)
            .graphic
            .lookupAll(".file-marker")
            .filter { it.isVisible && it.parent.isVisible }
            .sortedBy { it.localToScene(0.0, 0.0).x }
            .map { node -> node.styleClass.single { it != "file-marker" } }

    /** The label showing [name]. */
    private fun nameLabelOf(
        robot: FxRobot,
        name: String,
    ): Label = cellNamed(robot, name).graphic.lookup(".label") as Label

    /** The style class colouring [name], if any. */
    private fun nameColourOf(
        robot: FxRobot,
        name: String,
    ): String? = nameLabelOf(robot, name).styleClass.singleOrNull { it.startsWith("file-name-") }

    @Test
    fun `shows the bubbles given for each entry after its name, in order`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        project.resolve("changed.kt").createFile()
        project.resolve("clean.kt").createFile()
        var shown =
            mapOf(
                "sub" to FileMarkers(bubbles = listOf(FileMarker.UNVERSIONED, FileMarker.ADDED, FileMarker.MODIFIED)),
                "changed.kt" to FileMarkers(bubbles = listOf(FileMarker.MODIFIED)),
            )
        val asked = mutableListOf<Pair<String, Boolean>>()
        view.markers = { path, directory ->
            asked += path.name to directory
            shown[path.name] ?: FileMarkers.NONE
        }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            listOf("file-marker-unversioned", "file-marker-added", "file-marker-modified"),
            markersOf(robot, "sub"),
        )
        assertEquals(listOf("file-marker-modified"), markersOf(robot, "changed.kt"))
        assertEquals(emptyList(), markersOf(robot, "clean.kt"))
        assertTrue("sub" to true in asked && "clean.kt" to false in asked, asked.toString())
        // The markers follow the name.
        val cell = cellNamed(robot, "changed.kt")
        val label = cell.graphic.lookup(".label")
        val bubble = cell.graphic.lookup(".file-marker-modified")
        assertTrue(bubble.localToScene(0.0, 0.0).x > label.localToScene(label.layoutBounds.maxX, 0.0).x)

        shown = mapOf("clean.kt" to FileMarkers(bubbles = listOf(FileMarker.IGNORED)))
        robot.interact { view.refreshMarkers() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), markersOf(robot, "sub"))
        assertEquals(emptyList(), markersOf(robot, "changed.kt"))
        assertEquals(listOf("file-marker-ignored"), markersOf(robot, "clean.kt"))
    }

    @Test
    fun `colours the name as given and back after a refresh`(robot: FxRobot) {
        project.resolve("changed.kt").createFile()
        project.resolve("clean.kt").createFile()
        var shown = mapOf("changed.kt" to FileMarkers(name = FileMarker.MODIFIED))
        view.markers = { path, _ -> shown[path.name] ?: FileMarkers.NONE }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("file-name-modified", nameColourOf(robot, "changed.kt"))
        assertEquals(Color.web("#1e63c8"), nameLabelOf(robot, "changed.kt").textFill)
        assertEquals(emptyList(), markersOf(robot, "changed.kt"))
        assertNull(nameColourOf(robot, "clean.kt"))
        val normal = cellNamed(robot, "clean.kt").textFill
        assertEquals(normal, nameLabelOf(robot, "clean.kt").textFill)

        // On the selection bar the name has the selection's text colour, so it stays readable.
        robot.interact { view.selectionModel.select(view.root.children.first { it.value.name == "changed.kt" }) }
        WaitForAsyncUtils.waitForFxEvents()
        assertNull(nameColourOf(robot, "changed.kt"))
        assertEquals(cellNamed(robot, "changed.kt").textFill, nameLabelOf(robot, "changed.kt").textFill)
        robot.interact { view.selectionModel.clearSelection() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("file-name-modified", nameColourOf(robot, "changed.kt"))

        shown = emptyMap()
        robot.interact { view.refreshMarkers() }
        WaitForAsyncUtils.waitForFxEvents()

        assertNull(nameColourOf(robot, "changed.kt"))
        assertEquals(normal, nameLabelOf(robot, "changed.kt").textFill)
    }

    @Test
    fun `reports changed files and refreshes`(robot: FxRobot) {
        var reports = 0
        view.onFilesChanged = { reports++ }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        project.resolve("new.txt").createFile()
        eventually(robot) { assertEquals(1, reports) }

        robot.interact { view.refreshAll() }
        assertEquals(2, reports)
    }

    @Test
    fun `markers have a colour in both themes`(robot: FxRobot) {
        project.resolve("a.kt").createFile()
        var nameMarker = FileMarker.UNVERSIONED
        view.markers = { _, _ -> FileMarkers(name = nameMarker, bubbles = FileMarker.entries) }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        fun bubbleColours() =
            FileMarker.entries.map {
                (cellNamed(robot, "a.kt").graphic.lookup(".${it.bubbleStyleClass}") as Circle).fill
            }

        // Name colours equal the bubble colours.
        fun nameColours() =
            FileMarker.entries.map {
                nameMarker = it
                robot.interact { view.refreshMarkers() }
                WaitForAsyncUtils.waitForFxEvents()
                nameLabelOf(robot, "a.kt").textFill
            }
        val light = bubbleColours()
        assertEquals(Color.web("#d32f2f"), light.first())
        assertEquals(light, nameColours())
        robot.interact { view.scene.preferences.colorScheme = ColorScheme.DARK }
        WaitForAsyncUtils.waitForFxEvents()
        val dark = bubbleColours()
        assertEquals(Color.web("#ef6461"), dark.first())
        assertEquals(dark, nameColours())
        assertEquals(4, light.distinct().size)
        assertTrue(light.zip(dark).all { (l, d) -> l != d })
    }

    @Test
    fun `shows an icon per file type, readable or binary for unknown types`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        project.resolve("Main.kt").createFile()
        project.resolve("notes.adoc").createFile()
        project.resolve("blob.bin").createFile()
        val checked = mutableListOf<String>()
        view.canOpenFile = { file ->
            checked += file.name
            file.name != "blob.bin"
        }

        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            assertEquals(FileIcon.FOLDER_OPEN.ikon, iconOf(robot, project.name).iconCode)
            assertEquals(FileIcon.FOLDER.ikon, iconOf(robot, "sub").iconCode)
            assertEquals(FileIcon.KOTLIN.ikon, iconOf(robot, "Main.kt").iconCode)
            assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "notes.adoc").iconCode)
            assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "blob.bin").iconCode)
            assertTrue("file-icon-kotlin" in iconOf(robot, "Main.kt").styleClass)
            // The colour comes from file-explorer.css.
            assertEquals(Color.web("#7f52ff"), iconOf(robot, "Main.kt").iconColor)
        }
        // Only files without a known extension are checked, each once however often its cell is rendered.
        robot.interact { view.refresh() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("blob.bin", "notes.adoc"), checked.sorted())
    }

    @Test
    fun `the folder icon opens and closes with the folder`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.root.isExpanded = false }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals(FileIcon.FOLDER.ikon, iconOf(robot, project.name).iconCode) }

        robot.interact { view.root.isExpanded = true }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals(FileIcon.FOLDER_OPEN.ikon, iconOf(robot, project.name).iconCode) }
    }

    @Test
    fun `a file that changes on disk is checked again`(robot: FxRobot) {
        val file = project.resolve("data")
        file.writeBytes(byteArrayOf(0, 1, 2))
        view.canOpenFile = { 0.toByte() !in Files.readAllBytes(it) }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "data").iconCode) }

        file.writeText("now text")

        eventually(robot) { assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "data").iconCode) }
    }

    @Test
    fun `a manual refresh checks files again`(robot: FxRobot) {
        // Without working watches, only the refresh can notice that the file became text.
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        val file = project.resolve("data")
        file.writeBytes(byteArrayOf(0, 1, 2))
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings)
            manual.canOpenFile = { 0.toByte() !in Files.readAllBytes(it) }
            view.scene.root = manual
            manual.open(project)
        }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "data").iconCode) }

        file.writeText("now text")
        robot.interact { manual.refreshAll() }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "data").iconCode) }
    }

    @Test
    fun `icons are sized to the explorer font`(robot: FxRobot) {
        project.resolve("README.md").createFile()
        robot.interact { view.open(project) }
        robot.interact { fontSettings.apply(mapOf(FontArea.EXPLORER to FontSpec("Serif", 20.0))) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { assertEquals(25, iconOf(robot, "README.md").iconSize) }
    }

    @Test
    fun `icon colours follow the theme`(robot: FxRobot) {
        project.resolve("Main.kt").createFile()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        fun kotlinColor(scheme: ColorScheme): Any? {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            var color: Any? = null
            robot.interact { color = iconOf(robot, "Main.kt").iconColor }
            return color
        }
        assertEquals(Color.web("#7f52ff"), kotlinColor(ColorScheme.LIGHT))
        assertEquals(Color.web("#a78bfa"), kotlinColor(ColorScheme.DARK))
        assertEquals(Color.web("#7f52ff"), kotlinColor(ColorScheme.LIGHT))
    }
}
