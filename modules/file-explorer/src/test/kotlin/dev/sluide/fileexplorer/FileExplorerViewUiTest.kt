package dev.sluide.fileexplorer

import javafx.scene.Scene
import javafx.scene.control.TreeCell
import javafx.stage.Stage
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
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Renders the real view in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewUiTest {
    @TempDir
    lateinit var project: Path

    private lateinit var view: FileExplorerView

    @Start
    fun start(stage: Stage) {
        view = FileExplorerView()
        stage.scene = Scene(view, 400.0, 600.0)
        stage.show()
    }

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
}
