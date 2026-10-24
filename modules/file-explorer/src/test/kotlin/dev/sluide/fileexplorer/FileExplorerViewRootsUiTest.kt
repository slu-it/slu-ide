package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.MenuItem
import javafx.scene.control.TextField
import javafx.scene.control.TreeCell
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The explorer with an extra top-level folder after the project. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewRootsUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var scratches: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private lateinit var other: TextField
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private val opened = mutableListOf<Path>()
    private val extras get() = listOf(ExplorerRoot(scratches, "Scratches"))

    @Start
    fun start(stage: Stage) {
        val fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        view.onOpenFile = { opened.add(it) }
        view.askName = { _, _, _ -> "new.txt" }
        other = TextField()
        stage.scene = Scene(VBox(other, view), 400.0, 600.0)
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

    private fun menuItemFor(
        robot: FxRobot,
        node: Node,
        text: String,
    ): MenuItem {
        robot.interact {
            Event.fireEvent(
                node,
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, false, null),
            )
        }
        val item = view.contextMenu.items.single { it.text == text }
        robot.interact { view.contextMenu.hide() }
        return item
    }

    private fun scratchesItem() = view.root.children.last() as FileTreeItem

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
            val error = failure ?: return
            if (System.nanoTime() > deadline) throw error
            Thread.sleep(50)
        }
    }

    private fun openBoth(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        robot.interact { view.open(project, extras) }
    }

    @Test
    fun `shows the project expanded and the extra root collapsed`(robot: FxRobot) {
        scratches.resolve("s.txt").createFile()
        openBoth(robot)

        assertEquals(listOf(project.name, "a.txt", "Scratches"), renderedNames(robot))
        assertEquals(2, view.root.children.size)
        assertFalse(scratchesItem().isLoaded)
    }

    @Test
    fun `the extra root has a tooltip with its path and the project has none`(robot: FxRobot) {
        openBoth(robot)

        assertEquals(scratches.toString(), cellNamed(robot, "Scratches").tooltip?.text)
        assertNull(cellNamed(robot, project.name).tooltip)
        assertNull(cellNamed(robot, "a.txt").tooltip)
    }

    @Test
    fun `expanding the extra root lists its files and watches it`(robot: FxRobot) {
        scratches.resolve("sub").createDirectories()
        scratches.resolve("b.txt").createFile()
        scratches.resolve("a.txt").createFile()
        openBoth(robot)

        robot.interact { scratchesItem().isExpanded = true }
        assertEquals(listOf(project.name, "a.txt", "Scratches", "sub", "a.txt", "b.txt"), renderedNames(robot))

        scratches.resolve("c.txt").createFile()
        eventually(robot) {
            assertEquals(
                listOf("sub", "a.txt", "b.txt", "c.txt"),
                scratchesItem().children.map { it.value.name },
            )
        }
    }

    @Test
    fun `New File on the empty area creates in the project`(robot: FxRobot) {
        openBoth(robot)

        val newFile = menuItemFor(robot, view, "New File…")
        assertFalse(newFile.isDisable)
        robot.interact { newFile.fire() }

        assertTrue(project.resolve("new.txt").exists())
        assertFalse(scratches.resolve("new.txt").exists())
    }

    @Test
    fun `New File on the extra root creates inside it and opens the file`(robot: FxRobot) {
        openBoth(robot)

        val newFile = menuItemFor(robot, cellNamed(robot, "Scratches"), "New File…")
        assertFalse(newFile.isDisable)
        robot.interact { newFile.fire() }

        assertTrue(scratches.resolve("new.txt").exists())
        assertFalse(project.resolve("new.txt").exists())
        assertEquals(listOf(scratches.resolve("new.txt")), opened)
    }

    @Test
    fun `Rename and Delete are disabled on both top-level rows and enabled inside the extra root`(robot: FxRobot) {
        scratches.resolve("s.txt").createFile()
        openBoth(robot)
        robot.interact { scratchesItem().isExpanded = true }

        for (name in listOf(project.name, "Scratches")) {
            assertTrue(menuItemFor(robot, cellNamed(robot, name), "Rename…").isDisable, name)
            assertTrue(menuItemFor(robot, cellNamed(robot, name), "Delete…").isDisable, name)
        }
        // The empty area stands for the opened folder.
        assertTrue(menuItemFor(robot, view, "Rename…").isDisable)
        assertTrue(menuItemFor(robot, view, "Delete…").isDisable)
        val file = cellNamed(robot, "s.txt")
        assertFalse(menuItemFor(robot, file, "Rename…").isDisable)
        assertFalse(menuItemFor(robot, file, "Delete…").isDisable)
    }

    @Test
    fun `Refresh reads the loaded folders of both roots again`(robot: FxRobot) {
        openBoth(robot)
        robot.interact { scratchesItem().isExpanded = true }

        project.resolve("p.txt").createFile()
        scratches.resolve("s.txt").createFile()
        robot.interact { view.refreshAll() }

        assertEquals(listOf("a.txt", "p.txt"), view.projectNode.children.map { it.value.name })
        assertEquals(listOf("s.txt"), scratchesItem().children.map { it.value.name })
    }

    @Test
    fun `the start width accounts for the label of an extra root, not the folder name`(robot: FxRobot) {
        robot.interact { view.open(null, listOf(ExplorerRoot(scratches, "S"))) }
        val short = view.startWidth()

        robot.interact { view.open(null, listOf(ExplorerRoot(scratches, "A rather long label for a folder"))) }

        assertTrue(view.startWidth() > short)
    }

    @Test
    fun `reveal expands a never loaded extra root down to a nested file and keeps the focus`(robot: FxRobot) {
        val file = scratches.resolve("one/two/f.txt").also { it.parent.createDirectories() }.also { it.createFile() }
        openBoth(robot)
        robot.interact { other.requestFocus() }

        var result = false
        robot.interact { result = view.reveal(file) }

        assertTrue(result)
        assertEquals(file, view.selectionModel.selectedItem?.value)
        assertEquals(listOf(project.name, "a.txt", "Scratches", "one", "two", "f.txt"), renderedNames(robot))
        assertTrue(other.isFocused)
        assertFalse(view.isFocused)
    }

    @Test
    fun `reveal finds a file created just before, in a loaded folder`(robot: FxRobot) {
        openBoth(robot)
        robot.interact { scratchesItem().isExpanded = true }
        val file = scratches.resolve("fresh.txt").createFile()

        var result = false
        robot.interact { result = view.reveal(file) }

        assertTrue(result)
        assertEquals(file, view.selectionModel.selectedItem?.value)
    }

    @Test
    fun `reveal can select a top-level folder itself`(robot: FxRobot) {
        openBoth(robot)

        var result = false
        robot.interact { result = view.reveal(scratches) }

        assertTrue(result)
        assertEquals(scratches, view.selectionModel.selectedItem?.value)
    }

    @Test
    fun `reveal returns false for a path outside the roots or missing`(robot: FxRobot) {
        openBoth(robot)
        val selected = view.selectionModel.selectedItem

        var outside = true
        var missing = true
        robot.interact {
            outside = view.reveal(settingsDir.resolve("x.txt").createFile())
            missing = view.reveal(scratches.resolve("missing.txt"))
        }

        assertFalse(outside)
        assertFalse(missing)
        assertEquals(selected, view.selectionModel.selectedItem)
        assertFalse(scratchesItem().isExpanded)
    }

    @Test
    fun `reveal with focus focuses the tree`(robot: FxRobot) {
        val file = scratches.resolve("f.txt").createFile()
        openBoth(robot)
        robot.interact { other.requestFocus() }

        robot.interact { view.reveal(file, focus = true) }

        assertTrue(view.isFocused)
        assertEquals(file, view.selectionModel.selectedItem?.value)
    }

    @Test
    fun `opening only extra roots shows just them, opening null clears`(robot: FxRobot) {
        scratches.resolve("s.txt").createFile()

        robot.interact { view.open(null, extras) }
        assertEquals(listOf("Scratches"), renderedNames(robot))
        assertNull(view.projectItem)
        assertNotNull(view.root)

        robot.interact { view.open(null) }
        assertEquals(emptyList(), renderedNames(robot))
        assertNull(view.root)
    }

    @Test
    fun `without a project the empty area cannot create but an extra root can`(robot: FxRobot) {
        robot.interact { view.open(null, extras) }

        assertTrue(menuItemFor(robot, view, "New File…").isDisable)
        assertTrue(menuItemFor(robot, view, "New Folder…").isDisable)
        assertFalse(menuItemFor(robot, cellNamed(robot, "Scratches"), "New File…").isDisable)
    }

    @Test
    fun `a folder shown twice is watched in both copies`(robot: FxRobot) {
        val inner = project.resolve("inner").createDirectories()
        robot.interact { view.open(project, listOf(ExplorerRoot(inner, "Inner"))) }
        robot.interact {
            view.projectNode.children
                .single()
                .isExpanded = true
            scratchesItem().isExpanded = true
        }

        inner.resolve("both.txt").createFile()

        eventually(robot) {
            assertEquals(listOf("both.txt"), scratchesItem().children.map { it.value.name })
            assertEquals(
                listOf("both.txt"),
                view.projectNode.children
                    .single()
                    .children
                    .map { it.value.name },
            )
        }
    }

    @Test
    fun `reveal picks the deepest top-level folder holding the path`(robot: FxRobot) {
        val inner = project.resolve("inner").createDirectories()
        val file = inner.resolve("f.txt").createFile()
        robot.interact { view.open(project, listOf(ExplorerRoot(inner, "Inner"))) }

        var result = false
        robot.interact { result = view.reveal(file) }

        assertTrue(result)
        assertEquals(file, view.selectionModel.selectedItem?.value)
        assertTrue(view.selectionModel.selectedItem?.parent === scratchesItem())
        assertFalse(
            view.projectNode.children
                .single()
                .isExpanded,
        )
    }

    @Test
    fun `reveal goes through a symbolic link`(robot: FxRobot) {
        val real = settingsDir.resolve("real").createDirectories()
        val file = real.resolve("f.txt").createFile()
        val link = scratches.resolve("link")
        try {
            Files.createSymbolicLink(link, real)
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "no symbolic links: $e")
        } catch (e: java.io.IOException) {
            assumeTrue(false, "no symbolic links: $e")
        }
        openBoth(robot)

        var result = false
        robot.interact { result = view.reveal(link.resolve("f.txt")) }

        assertTrue(result)
        assertEquals(link.resolve("f.txt"), view.selectionModel.selectedItem?.value)
        assertTrue(file.exists())
    }

    @Test
    fun `reveal fails and expands nothing when the extra root was no folder when opened`(robot: FxRobot) {
        val later = scratches.resolve("later")
        robot.interact { view.open(project, listOf(ExplorerRoot(later, "Later"))) }
        later.createDirectories()
        val file = later.resolve("f.txt").createFile()

        var result = true
        robot.interact { result = view.reveal(file) }

        assertFalse(result)
        assertFalse(scratchesItem().isExpanded)
        assertNull(view.selectionModel.selectedItem)
    }

    @Test
    fun `a folder dropped from the tree loses its watch`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        val changes = mutableListOf<Set<Path>>()
        view.onFilesChanged = { changes.add(it) }
        robot.interact { view.open(project) }
        robot.interact {
            view.projectNode.children
                .single()
                .isExpanded = true
        }

        robot.interact { view.projectNode.children.clear() }
        project.resolve("x.txt").createFile()
        eventually(robot) { assertTrue(changes.any { project.resolve("x.txt") in it }) }
        changes.clear()
        sub.resolve("y.txt").createFile()
        Thread.sleep(700)
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(changes.none { change -> change.any { it.startsWith(sub) } })
    }

    @Test
    fun `the watches of an extra root end on open null and on a second open`(robot: FxRobot) {
        val changes = mutableListOf<Set<Path>>()
        view.onFilesChanged = { changes.add(it) }
        openBoth(robot)
        robot.interact { scratchesItem().isExpanded = true }
        changes.clear()

        robot.interact { view.open(null) }
        scratches.resolve("one.txt").createFile()
        Thread.sleep(700)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), changes)

        robot.interact { view.open(project, extras) }
        robot.interact { scratchesItem().isExpanded = true }
        robot.interact { view.open(project, extras) }
        changes.clear()
        scratches.resolve("two.txt").createFile()
        Thread.sleep(700)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), changes)
    }
}
