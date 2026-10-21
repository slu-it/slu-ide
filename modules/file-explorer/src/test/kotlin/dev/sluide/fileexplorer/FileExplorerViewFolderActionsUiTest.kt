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
import javafx.scene.control.TreeCell
import javafx.scene.input.ContextMenuEvent
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The folder actions the window adds to the explorer's context menu. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewFolderActionsUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private val opened = mutableListOf<Path>()
    private val errors = mutableListOf<String>()
    private val infos = mutableListOf<String>()
    private val ranOn = mutableListOf<Path>()

    @Start
    fun start(stage: Stage) {
        val fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        view.onOpenFile = { opened.add(it) }
        view.showError = { errors.add(it) }
        view.showInfo = { infos.add(it) }
        stage.scene = Scene(view, 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    private fun cellNamed(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .first { it.text == name }

    private fun menuTexts(
        robot: FxRobot,
        node: Node,
    ): List<String?> {
        robot.interact {
            Event.fireEvent(
                node,
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, false, null),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
        val texts = view.contextMenu.items.map { it.text }
        robot.interact { view.contextMenu.hide() }
        return texts
    }

    private fun actionItem(
        robot: FxRobot,
        node: Node,
        label: String,
    ): MenuItem {
        menuTexts(robot, node)
        return view.contextMenu.items.single { it.text == label }
    }

    private fun fireAction(
        robot: FxRobot,
        node: Node,
    ) {
        val item = actionItem(robot, node, "Do it")
        robot.interact { item.fire() }
    }

    private fun action(
        label: String = "Do it",
        result: (Path) -> FolderActionResult,
    ) = FolderAction(label) {
        ranOn.add(it)
        result(it)
    }

    private fun openProject(robot: FxRobot) {
        project.resolve("sub/inner.txt").createParentDirectories().createFile()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `the items follow New Folder, also when the actions are set after the menu was built`(robot: FxRobot) {
        openProject(robot)
        assertEquals(false, menuTexts(robot, view).contains("Do it"))

        view.folderActions =
            listOf(action("First") { FolderActionResult(null) }, action("Second") { FolderActionResult(null) })
        val texts = menuTexts(robot, view)
        val newFolder = texts.indexOf("New Folder…")
        assertEquals(listOf("First", "Second"), texts.subList(newFolder + 1, newFolder + 3))
        assertEquals(null, texts[newFolder + 3], "a separator follows")

        // Showing the menu again does not duplicate them.
        assertEquals(texts, menuTexts(robot, view))
    }

    @Test
    fun `the items are disabled without an opened folder`(robot: FxRobot) {
        view.folderActions = listOf(action { FolderActionResult(null) })

        assertTrue(actionItem(robot, view, "Do it").isDisable)
    }

    @Test
    fun `the action runs on the clicked folder, the folder of a clicked file, or the opened folder`(robot: FxRobot) {
        openProject(robot)
        robot.interact {
            view.projectNode.children
                .single()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()
        view.folderActions = listOf(action { FolderActionResult(null) })

        fireAction(robot, cellNamed(robot, "sub"))
        fireAction(robot, cellNamed(robot, "inner.txt"))
        fireAction(robot, view)

        assertEquals(listOf(project.resolve("sub"), project.resolve("sub"), project), ranOn)
    }

    @Test
    fun `the returned file is selected and opened, and the note is shown`(robot: FxRobot) {
        openProject(robot)
        val created = project.resolve("sub/new.txt")
        view.folderActions =
            listOf(
                action {
                    it.resolve("new.txt").writeText("x")
                    FolderActionResult(created, "Created it")
                },
            )

        fireAction(robot, cellNamed(robot, "sub"))
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { assertEquals(created, view.selectionModel.selectedItem?.value) }
        assertEquals(listOf(created), opened)
        assertEquals(listOf("Created it"), infos)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `an action without a file expands the collapsed folder and shows the new entry at once`(robot: FxRobot) {
        openProject(robot)
        val sub = view.projectNode.children.single() as FileTreeItem
        view.folderActions =
            listOf(
                action {
                    it.resolve("made.txt").createFile()
                    FolderActionResult(null)
                },
            )

        robot.interact { view.runFolderAction(view.folderActions.single(), sub) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            assertTrue(sub.isExpanded)
            assertEquals(setOf("inner.txt", "made.txt"), sub.children.map { it.value.name }.toSet())
        }
        assertEquals(emptyList(), opened)
        assertEquals(emptyList(), infos)
    }

    @Test
    fun `a file elsewhere is opened but not selected, the folder is expanded and shows the new entry`(robot: FxRobot) {
        openProject(robot)
        val sub = view.projectNode.children.single() as FileTreeItem
        val elsewhere = project.resolve("other.txt").createFile()
        view.folderActions =
            listOf(
                action {
                    it.resolve("made.txt").createFile()
                    FolderActionResult(elsewhere)
                },
            )

        robot.interact { view.runFolderAction(view.folderActions.single(), sub) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(elsewhere), opened)
        robot.interact {
            assertTrue(sub.isExpanded)
            assertTrue(sub.children.any { it.value.name == "made.txt" })
            assertTrue(view.selectionModel.selectedItem?.value != elsewhere)
        }
    }

    @Test
    fun `the message of an UncheckedIOException's cause is shown as an error`(robot: FxRobot) {
        openProject(robot)
        view.folderActions = listOf(action { throw UncheckedIOException(IOException("Disk full")) })

        robot.interact { view.runFolderAction(view.folderActions.single(), null) }

        assertEquals(listOf("Disk full"), errors)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `an IOException is shown as an error and nothing is opened`(robot: FxRobot) {
        openProject(robot)
        view.folderActions = listOf(action { throw IOException("No space left") })

        robot.interact { view.runFolderAction(view.folderActions.single(), null) }

        assertEquals(listOf("No space left"), errors)
        assertEquals(emptyList(), opened)
        assertEquals(emptyList(), infos)
    }
}
