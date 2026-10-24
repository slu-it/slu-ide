package dev.sluide.fileexplorer

import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.input.ContextMenuEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The folder actions the window adds to the explorer's context menu. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewFolderActionsUiTest : FileExplorerViewUiFixture() {
    private val ranOn = mutableListOf<Path>()

    @Start
    fun start(stage: Stage) = startView(stage)

    private fun menuTexts(
        robot: FxRobot,
        node: Node,
        keyboard: Boolean = false,
    ): List<String?> {
        robot.interact {
            Event.fireEvent(
                node,
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, keyboard, null),
            )
        }
        val texts = view.contextMenu.items.map { it.text }
        robot.interact { view.contextMenu.hide() }
        return texts
    }

    private fun actionItem(
        robot: FxRobot,
        node: Node,
        label: String,
        keyboard: Boolean = false,
    ): MenuItem {
        menuTexts(robot, node, keyboard)
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
    fun `the action runs on the clicked folder or the opened folder, and is disabled on a file`(robot: FxRobot) {
        openProject(robot)
        robot.interact {
            view.projectNode.children
                .single()
                .isExpanded = true
        }
        view.folderActions = listOf(action { FolderActionResult(null) })

        fireAction(robot, cellNamed(robot, "sub"))
        assertTrue(actionItem(robot, cellNamed(robot, "inner.txt"), "Do it").isDisable)
        fireAction(robot, view)

        assertEquals(listOf(project.resolve("sub"), project), ranOn)
        // Several entries are no folder.
        robot.interact {
            view.selectionModel.clearSelection()
            view.selectionModel.select(view.projectNode.children.single())
            view.selectionModel.select(
                view.projectNode.children
                    .single()
                    .children
                    .single(),
            )
        }
        assertTrue(actionItem(robot, view, "Do it", keyboard = true).isDisable)
    }
}
