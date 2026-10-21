package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.MoveQuestion
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.ScrollBar
import javafx.scene.control.TreeCell
import javafx.scene.input.MouseButton
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
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real drag and drop gestures with robot input, against the rule to avoid real input (`docs/testing.md`): a drag can
 * only be tested this way, since DRAG_OVER and DRAG_DROPPED need a `Dragboard`, which only a gesture started by the
 * platform from real mouse input creates; synthetic `MouseEvent`s never start one. The move flow itself is tested
 * without input in [FileExplorerViewMoveUiTest]. Needs a display, see `uiTest`.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewDragUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private val questions = mutableListOf<MoveQuestion>()

    @Start
    fun start(stage: Stage) {
        val fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        view.confirmMove = {
            questions += it
            true
        }
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

    private fun open(robot: FxRobot) {
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (!condition() && System.nanoTime() < deadline) {
            WaitForAsyncUtils.waitForFxEvents()
            Thread.sleep(50)
        }
    }

    private fun TreeCell<*>.isDropTarget() = pseudoClassStates.any { it.pseudoClassName == "drop-target" }

    @Test
    fun `dragging a file onto a folder marks it, expands it when held and moves the file after the question`(
        robot: FxRobot,
    ) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        project.resolve("target/inside.txt").createFile()
        open(robot)
        val target = cellNamed(robot, "target")

        val drag = robot.drag(cellNamed(robot, "a.txt"), MouseButton.PRIMARY).moveTo(target)
        waitFor { target.isDropTarget() }
        assertTrue(target.isDropTarget())
        waitFor {
            view.projectNode.children
                .first()
                .isExpanded
        }
        assertTrue(
            view.projectNode.children
                .first()
                .isExpanded,
            "held long enough, the folder expands",
        )
        drag.drop()

        waitFor { project.resolve("target/a.txt").exists() }
        assertEquals("Move “a.txt” to “target”?", questions.single().header)
        assertTrue(project.resolve("target/a.txt").exists())
        assertFalse(target.isDropTarget())
    }

    @Test
    fun `a file is no drop target`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        open(robot)
        val other = cellNamed(robot, "b.txt")

        val drag = robot.drag(cellNamed(robot, "a.txt"), MouseButton.PRIMARY).moveTo(other)
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(other.isDropTarget())
        drag.drop()
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), questions)
        assertTrue(project.resolve("a.txt").exists())
    }

    @Test
    fun `a drag ending without a drop selects the dragged entries again`(robot: FxRobot) {
        project.resolve("target").createDirectories()
        project.resolve("target/inside.txt").createFile()
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        open(robot)
        val folder = view.projectNode.children.first()

        // The folder above the dragged entry expands, which can lose the selection.
        val drag = robot.drag(cellNamed(robot, "a.txt"), MouseButton.PRIMARY).moveTo(cellNamed(robot, "target"))
        waitFor { folder.isExpanded }
        drag.dropTo(cellNamed(robot, "b.txt"))
        waitFor { view.selectionModel.selectedItems.size == 1 }

        assertEquals(emptyList(), questions)
        assertEquals(listOf(project.resolve("a.txt")), view.selectionModel.selectedItems.map { it.value })
    }

    @Test
    fun `the scroll bar is no drop target`(robot: FxRobot) {
        project.resolve("src").createDirectories()
        project.resolve("src/a.txt").createFile()
        (1..60).forEach { project.resolve("f%02d.txt".format(it)).createFile() }
        open(robot)
        robot.interact {
            view.projectNode.children
                .first()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()
        val bar =
            robot
                .lookup(".scroll-bar")
                .queryAllAs(ScrollBar::class.java)
                .first { it.orientation == Orientation.VERTICAL && it.isVisible }

        robot.drag(cellNamed(robot, "a.txt"), MouseButton.PRIMARY).dropTo(bar)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), questions)
        assertTrue(project.resolve("src/a.txt").exists())
    }

    @Test
    fun `the empty area below the rows stands for the project folder`(robot: FxRobot) {
        project.resolve("src").createDirectories()
        project.resolve("src/a.txt").createFile()
        open(robot)
        robot.interact {
            view.projectNode.children
                .first()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()

        robot
            .drag(cellNamed(robot, "a.txt"), MouseButton.PRIMARY)
            .dropTo(robot.point(view.localToScreen(view.width / 2, view.height - 50)))

        waitFor { project.resolve("a.txt").exists() }
        assertEquals("Move “a.txt” to “${project.fileName}”?", questions.single().header)
        assertTrue(project.resolve("a.txt").exists())
    }
}
