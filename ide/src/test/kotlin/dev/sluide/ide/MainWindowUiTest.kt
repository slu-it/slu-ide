package dev.sluide.ide

import dev.sluide.editor.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.terminal.Terminals
import javafx.application.Platform
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.TextArea
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
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
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** Shows the real main window. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var otherProject: Path

    private val projectService = ProjectService()
    private lateinit var stage: Stage
    private lateinit var fileExplorer: FileExplorerView
    private lateinit var editorArea: EditorArea
    private lateinit var mainWindow: MainWindow
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private val terminals = Terminals()

    @AfterEach
    fun close() {
        terminals.close()
        watcher.close()
    }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        fileExplorer = FileExplorerView(watcher)
        val fallback = DefaultTextFileViewer()
        editorArea = EditorArea(FileViewers(listOf(fallback), fallback), watcher)
        mainWindow = MainWindow(projectService, fileExplorer, editorArea, terminals)
        mainWindow.show(stage)
    }

    @Test
    fun `starts with an empty explorer and the plain title`(robot: FxRobot) {
        assertEquals("SluIde", stage.title)
        assertEquals(0, robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).count { it.text != null })
    }

    @Test
    fun `offers File - Open Folder with Ctrl+O`(robot: FxRobot) {
        val fileMenu = menu(robot, "File")
        val openFolder = fileMenu.items.single()

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

    @Test
    fun `opening a file in the explorer shows it in a read-only tab`(robot: FxRobot) {
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        robot.interact { projectService.open(project) }

        robot.interact { fileExplorer.onOpenFile?.invoke(readme) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("README.md"), tabTitles(robot))
        val text = robot.lookup(".editor-content .text-area").queryAs(TextArea::class.java)
        assertEquals("# Hello", text.text)
        assertEquals(false, text.isEditable)
    }

    @Test
    fun `opening another folder closes all tabs, reopening the current one keeps them`(robot: FxRobot) {
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        robot.interact {
            projectService.open(project)
            fileExplorer.onOpenFile?.invoke(readme)
        }

        robot.interact { projectService.open(project) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("README.md"), tabTitles(robot))

        robot.interact { projectService.open(otherProject) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, robot.lookup(".editor-content .text-area").queryAll<TextArea>().size)
    }

    @Test
    fun `offers Terminal - New Terminal with Ctrl+Shift+T`(robot: FxRobot) {
        val newTerminal = menu(robot, "Terminal").items.single()

        assertEquals("New Terminal", newTerminal.text)
        val accelerator = newTerminal.accelerator as KeyCodeCombination
        assertEquals(KeyCode.T, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shift)
    }

    @Test
    fun `a new terminal opens as a tab in the project folder, opening another folder closes it`(robot: FxRobot) {
        robot.interact { projectService.open(project) }

        robot.interact { menu(robot, "Terminal").items.single().fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("Terminal 1"), tabTitles(robot))
        val tab = checkNotNull(editorArea.selectedTab)
        assertEquals("Terminal in $project", tab.tooltip)

        robot.interact { projectService.open(otherProject) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)
    }

    private fun menu(
        robot: FxRobot,
        text: String,
    ): Menu =
        robot
            .lookup(".menu-bar")
            .queryAs(MenuBar::class.java)
            .menus
            .single { it.text == text }

    private fun tabTitles(robot: FxRobot): List<String> =
        robot.lookup(".editor-tab .label").queryAllAs(Label::class.java).map { it.text }
}
