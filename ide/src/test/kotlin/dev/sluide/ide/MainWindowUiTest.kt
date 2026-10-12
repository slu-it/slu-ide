package dev.sluide.ide

import dev.sluide.fileexplorer.FileExplorerView
import javafx.scene.control.MenuBar
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
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

/** Shows the real main window. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowUiTest {
    @TempDir
    lateinit var project: Path

    private val projectService = ProjectService()
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        MainWindow(projectService, FileExplorerView()).show(stage)
    }

    @Test
    fun `starts with an empty explorer and the plain title`(robot: FxRobot) {
        assertEquals("SluIde", stage.title)
        assertEquals(0, robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).count { it.text != null })
    }

    @Test
    fun `offers File - Open Folder with Ctrl+O`(robot: FxRobot) {
        val fileMenu =
            robot
                .lookup(".menu-bar")
                .queryAs(MenuBar::class.java)
                .menus
                .single()
        val openFolder = fileMenu.items.single()

        assertEquals("File", fileMenu.text)
        assertEquals("Open Folder…", openFolder.text)
        val accelerator = openFolder.accelerator as KeyCodeCombination
        assertEquals(KeyCode.O, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
    }

    @Test
    fun `opening a project updates title and explorer including hidden files`(robot: FxRobot) {
        project.resolve(".git").createDirectories()
        project.resolve("README.md").createFile()

        robot.interact { projectService.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("${project.name} – SluIde", stage.title)
        val names =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .sortedBy { it.index }
                .mapNotNull { it.text }
        assertEquals(listOf(project.name, ".git", "README.md"), names)
    }
}
