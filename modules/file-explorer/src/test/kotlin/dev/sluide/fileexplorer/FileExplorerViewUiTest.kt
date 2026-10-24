package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** Renders the real view in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewUiTest : FileExplorerViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `renders hidden files and folders, folders first`(robot: FxRobot) {
        project.resolve(".hidden-dir").createDirectories()
        project.resolve("sub").createDirectories()
        project.resolve(".hidden-file").createFile()
        project.resolve("visible.txt").createFile()

        robot.interact { view.open(project) }

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
            view.projectNode.children
                .single()
                .isExpanded = true
        }

        assertEquals(listOf(project.name, "sub", "deeper", "inner.txt"), renderedNames(robot))
    }

    @Test
    fun `opening null clears the view`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        robot.interact { view.open(project) }

        robot.interact { view.open(null) }

        assertNull(view.root)
        assertEquals(emptyList(), renderedNames(robot))
    }

    @Test
    fun `Enter on a selected file requests opening it`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }

        robot.interact {
            view.selectOnly(view.projectNode.children.single())
            pressKey(KeyCode.ENTER)
            view.selectOnly(view.projectNode)
            pressKey(KeyCode.ENTER)
        }

        assertEquals(listOf(file), requested)
    }

    @Test
    fun `double-click on a file requests opening it, on a folder it does not`(robot: FxRobot) {
        val folder = project.resolve("sub").createDirectories()
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }

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
            val sub = view.projectNode.children.single()
            sub.isExpanded = true
            view.selectOnly(sub.children.single())
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
    fun `Refresh in the context menu and F5 re-read loaded folders`(robot: FxRobot) {
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
        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))

        project.resolve("later.txt").createFile()
        robot.interact {
            Event.fireEvent(manual, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false))
        }
        assertEquals(listOf(project.name, "late.txt", "later.txt"), renderedNames(robot))
    }

    @Test
    fun `a deleted and recreated subfolder keeps being watched`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        robot.interact {
            view.projectNode.children
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
}
