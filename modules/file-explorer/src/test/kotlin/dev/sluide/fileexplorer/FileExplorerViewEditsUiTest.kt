package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.DeleteQuestion
import dev.sluide.fileexplorer.actions.EntryKind
import dev.sluide.fileexplorer.actions.deleteQuestion
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.filewatcher.FileWatcher
import javafx.application.Platform
import javafx.css.PseudoClass
import javafx.event.Event
import javafx.scene.control.Button
import javafx.scene.input.Clipboard
import javafx.scene.input.DataFormat
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Creating, renaming and deleting entries, and the menu items for them, in the real view. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewEditsUiTest : FileExplorerViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** Replaces [view] by one whose watches never report, so only the view itself can show changes. */
    private fun useUnwatchedView(robot: FxRobot) {
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        robot.interact {
            val replaced = view
            view = FileExplorerView(unwatched, fontSettings, ExplorerClipboard())
            replaced.scene.root = view
        }
    }

    @Test
    fun `New File creates a file in the clicked folder, selects and opens it at once, and is disabled on a file`(
        robot: FxRobot,
    ) {
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
            view.projectNode.children
                .single()
                .isExpanded = true
        }

        assertTrue(menuItemFor(robot, cellNamed(robot, "taken.txt"), "New File…").isDisable)
        val newFile = menuItemFor(robot, cellNamed(robot, "sub"), "New File…")
        assertFalse(newFile.isDisable)
        robot.interact { newFile.fire() }

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

        val inSub = menuItemFor(robot, cellNamed(robot, "sub"), "New Folder…")
        robot.interact { inSub.fire() }
        assertTrue(Files.isDirectory(project.resolve("sub/nested")))
        assertEquals(listOf(project.name, "sub", "nested", "inner.txt"), renderedNames(robot))
        robot.interact {
            assertEquals(project.resolve("sub/nested"), view.selectionModel.selectedItem?.value)
            assertEquals(project.resolve("sub/nested"), view.focusModel.focusedItem?.value)
        }

        // The empty area below the entries stands for the opened folder.
        name = "top"
        val inProject = menuItemFor(robot, view, "New Folder…")
        assertFalse(inProject.isDisable)
        robot.interact { inProject.fire() }
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
        robot.interact { view.selectOnly(view.projectNode.children.single()) }

        robot.interact { view.create(null, folder = true) }

        robot.interact {
            assertEquals(project.resolve("a"), view.selectionModel.selectedItem?.value)
            assertEquals(project.resolve("a"), view.focusModel.focusedItem?.value)
        }
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

        val rename = menuItemFor(robot, cellNamed(robot, "old.txt"), "Rename…")
        assertFalse(rename.isDisable)
        robot.interact { rename.fire() }

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
            view.projectNode.children
                .single()
                .isExpanded = true
        }

        robot.interact { view.rename(view.projectNode.children.single() as FileTreeItem) }

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
            view.selectOnly(view.projectNode.children.single())
            pressKey(KeyCode.F2)
            view.selectOnly(view.projectNode)
            pressKey(KeyCode.F2)
        }

        assertEquals(listOf("a.txt"), asked)
        assertTrue(project.resolve("a.txt").exists())
    }

    @Test
    fun `Open in file manager starts the command for a folder, not for a file`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        val sub = project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        val commands = mutableListOf<List<String>>()
        view.launchCommand = { commands.add(it) }
        val label = view.fileManager.label

        assertTrue(menuItemFor(robot, cellNamed(robot, "a.txt"), label).isDisable)
        val item = menuItemFor(robot, cellNamed(robot, "sub"), label)
        assertFalse(item.isDisable)
        robot.interact { item.fire() }
        assertEquals(listOf(sub.toString()), commands.map { it.last() })
        val empty = menuItemFor(robot, view, label)
        assertFalse(empty.isDisable)
        robot.interact { empty.fire() }
        assertEquals(project.toString(), commands.last().last())
    }

    @Test
    fun `Open in file manager shows an error if the command cannot start`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        val errors = mutableListOf<String>()
        view.showError = { errors.add(it) }
        view.launchCommand = { throw IOException("no such program") }

        val item = menuItemFor(robot, cellNamed(robot, "sub"), view.fileManager.label)
        robot.interact { item.fire() }
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains(sub.toString()))
        assertTrue(errors.single().contains("no such program"))
    }

    @Test
    fun `Show History is in the menu only with its hook, enabled for files it can show`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val other = project.resolve("b.txt").createFile()
        project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        assertFalse(menuItemFor(robot, cellNamed(robot, "a.txt"), "Show History").isVisible)

        val shown = mutableListOf<Path>()
        val asked = mutableListOf<Path>()
        view.showHistory = { shown.add(it) }
        view.canShowHistory = { path ->
            asked.add(path)
            path == file
        }

        val history = menuItemFor(robot, cellNamed(robot, "a.txt"), "Show History")
        assertTrue(history.isVisible)
        assertFalse(history.isDisable)
        robot.interact { history.fire() }
        assertEquals(listOf(file), shown)
        assertTrue(menuItemFor(robot, cellNamed(robot, "b.txt"), "Show History").isDisable)
        // Folders and the empty area have none, without asking.
        assertTrue(menuItemFor(robot, cellNamed(robot, "sub"), "Show History").isDisable)
        assertTrue(menuItemFor(robot, view, "Show History").isDisable)
        assertEquals(listOf(file, other), asked)
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
            view.projectNode.children
                .first()
                .isExpanded = true
        }
        // As a right-click does.
        robot.interact { view.selectOnly(view.projectNode.children.first()) }

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
            view.selectOnly(view.projectNode.children.first())
            pressKey(KeyCode.DELETE)
        }

        assertTrue(file.exists())
        robot.interact {
            // The next entry is selected at once.
            assertEquals(project.resolve("b.txt"), view.selectionModel.selectedItem?.value)
            view.selectOnly(view.projectNode.children.first())
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
    fun `the context menu opened by keyboard is for the selected entry`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        val asked = mutableListOf<String>()
        view.askName = { _, initial, _ ->
            asked += initial
            null
        }
        robot.interact { view.open(project) }
        robot.interact { view.selectOnly(view.projectNode.children.single()) }

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
            view.selectOnly(view.projectNode.children.first())
            pressKey(KeyCode.DELETE)
            view.selectOnly(view.projectNode.children.last())
            pressKey(KeyCode.BACK_SPACE)
            // Control and Meta, so the shortcut key is down on every platform.
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, true, false, true))
            view.selectOnly(view.projectNode)
            pressKey(KeyCode.DELETE)
            // The opened folder among several entries: nothing is deleted either.
            view.selectOnly(view.projectNode.children.first())
            view.selectionModel.select(view.projectNode)
            pressKey(KeyCode.DELETE)
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, true, false, true))
        }

        assertEquals(listOf("Delete the file “a.txt”?", "Delete the file “b.txt”?"), asked)
    }

    /** Whether the row of [name] is dimmed as cut. */
    private fun isCut(
        robot: FxRobot,
        name: String,
    ): Boolean = PseudoClass.getPseudoClass("cut") in cellNamed(robot, name).pseudoClassStates

    /** The files and the text on the system clipboard. */
    private fun systemClipboard(): Pair<List<File>, String?> =
        WaitForAsyncUtils
            .asyncFx<Pair<List<File>, String?>> {
                val clipboard = Clipboard.getSystemClipboard()
                (if (clipboard.hasFiles()) clipboard.files else emptyList<File>()) to clipboard.string
            }.get()

    @Test
    fun `the shortcut key with C puts the selection on the system clipboard, with V it is pasted as a copy`(
        robot: FxRobot,
    ) {
        project.resolve("a.txt").writeText("a")
        robot.interact { view.open(project) }
        val file =
            view.projectNode.children
                .single()
                .value

        robot.interact {
            view.selectOnly(view.projectNode.children.single())
            pressKey(KeyCode.C, shortcut = true)
        }

        assertEquals(listOf(file.toFile()) to file.toString(), systemClipboard())
        robot.interact { pressKey(KeyCode.V, shortcut = true) }
        eventually(robot) {
            assertEquals("a", project.resolve("a-copy.txt").readText())
            assertEquals(listOf(file.resolveSibling("a-copy.txt")), view.selectionModel.selectedItems.map { it.value })
        }

        // Files another application put there, without their paths as text, paste as well.
        val outside = settingsDir.resolve("outside.txt").apply { writeText("outside") }
        robot.interact {
            Clipboard.getSystemClipboard().setContent(mapOf(DataFormat.FILES to listOf(outside.toFile())))
            pressKey(KeyCode.V, shortcut = true)
        }
        eventually(robot) { assertEquals("outside", project.resolve("outside.txt").readText()) }
    }

    @Test
    fun `the shortcut key with X dims the entry until Escape, and a paste after it moves the entry`(robot: FxRobot) {
        project.resolve("a.txt").writeText("a")
        project.resolve("target").createDirectories()
        val moved = mutableListOf<Pair<Path, Path>>()
        view.onMoved = { from, to -> moved += from to to }
        robot.interact { view.open(project) }
        val file =
            view.projectNode.children
                .last()
                .value

        robot.interact {
            view.selectOnly(view.projectNode.children.last())
            pressKey(KeyCode.X, shortcut = true)
        }
        assertTrue(isCut(robot, "a.txt"))
        assertFalse(isCut(robot, "target"))
        assertEquals(listOf(file.toFile()), systemClipboard().first)

        robot.interact { pressKey(KeyCode.ESCAPE) }
        assertFalse(isCut(robot, "a.txt"))
        assertEquals(emptyList(), systemClipboard().first, "Escape clears the clipboard holding the cut")

        robot.interact {
            pressKey(KeyCode.X, shortcut = true)
            view.selectOnly(view.projectNode.children.first())
            pressKey(KeyCode.V, shortcut = true)
        }

        val target = file.resolveSibling("target")
        assertEquals("a", target.resolve("a.txt").readText())
        assertFalse(file.exists())
        assertEquals(listOf(file to target.resolve("a.txt")), moved)
        assertEquals(emptyList(), systemClipboard().first, "the paste ends the cut")
    }

    @Test
    fun `a cut is undimmed when the explorer gets the focus while the clipboard holds something else`(robot: FxRobot) {
        project.resolve("a.txt").writeText("a")
        val other = Button("other")
        robot.interact {
            view.open(project)
            view.scene.root = HBox(other, view)
            view.selectOnly(view.projectNode.children.single())
            pressKey(KeyCode.X, shortcut = true)
            other.requestFocus()
            Clipboard.getSystemClipboard().setContent(mapOf(DataFormat.PLAIN_TEXT to "text"))
        }
        assertTrue(isCut(robot, "a.txt"), "no clipboard event: dimmed until checked")

        robot.interact { view.requestFocus() }

        assertFalse(isCut(robot, "a.txt"))
    }

    @Test
    fun `the context menu has Cut, Copy and Paste showing their keys, Paste only with files on the clipboard`(
        robot: FxRobot,
    ) {
        project.resolve("a.txt").writeText("a")
        robot.interact {
            view.open(project)
            Clipboard.getSystemClipboard().setContent(mapOf(DataFormat.PLAIN_TEXT to "text"))
        }
        val cell = cellNamed(robot, "a.txt")
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        val shortcutC = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, !mac, false, mac)
        assertTrue(COPY_KEYS.match(shortcutC))

        assertEquals(COPY_KEYS.displayText, menuItemFor(robot, cell, "Copy").accelerator.displayText)
        assertEquals(CUT_KEYS.displayText, menuItemFor(robot, cell, "Cut").accelerator.displayText)
        assertFalse(menuItemFor(robot, cell, "Cut").isDisable)
        assertTrue(menuItemFor(robot, cell, "Paste").isDisable)
        assertTrue(menuItemFor(robot, view, "Cut").isDisable, "nothing to cut on the empty area")
        // The keys are the explorer's own: a scene accelerator would copy entries while the editor has the focus.
        assertTrue(
            view.scene.accelerators.keys
                .isNotEmpty() &&
                view.scene.accelerators.keys
                    .none { it.match(shortcutC) },
        )

        val copy = menuItemFor(robot, cell, "Copy")
        robot.interact { copy.fire() }

        assertEquals(listOf(project.resolve("a.txt").toFile()), systemClipboard().first)
        val paste = menuItemFor(robot, view, "Paste")
        assertEquals(PASTE_KEYS.displayText, paste.accelerator.displayText)
        assertFalse(paste.isDisable)
        robot.interact { paste.fire() }
        eventually(robot) { assertTrue(project.resolve("a-copy.txt").exists()) }
    }
}
