package dev.sluide.ide

import dev.sluide.coloring.color.CssColor
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.TextArea
import javafx.scene.control.TreeCell
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI tests of the main window: settings, terminals and the Docker sandbox menu. Needs a display, see the `uiTest`
 * Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowToolsUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `offers File - Settings with Ctrl+Comma, which opens the settings dialog`(robot: FxRobot) {
        val settings = menu(robot, "File").items.last()
        assertEquals("Settings…", settings.text)
        val accelerator = settings.accelerator as KeyCodeCombination
        assertEquals(KeyCode.COMMA, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)

        var shownFor: Stage? = null
        mainWindow.showSettings = { shownFor = it }
        robot.interact { settings.fire() }
        assertEquals(stage, shownFor)
    }

    @Test
    fun `Open in Terminal in the explorer opens a terminal tab in the clicked folder`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        robot.interact { projectService.open(project) }
        val cell =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .single { (it.item as? Path)?.name == "sub" }
        robot.interact {
            Event.fireEvent(
                cell,
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, false, null),
            )
        }
        val item = fileExplorer.contextMenu.items.single { it.text == "Open in Terminal" }
        robot.interact { fileExplorer.contextMenu.hide() }

        robot.interact { item.fire() }

        assertEquals(listOf("Terminal 1"), tabTitles(robot))
        assertEquals("Terminal in $sub", checkNotNull(editorArea.selectedTab).tooltip)
    }

    @Test
    fun `a new terminal opens as a tab in the project folder, disposing the window ends it`(robot: FxRobot) {
        robot.interact { projectService.open(project) }

        robot.interact { menu(robot, "Terminal").items.single().fire() }

        assertEquals(listOf("Terminal 1"), tabTitles(robot))
        val tab = checkNotNull(editorArea.selectedTab)
        assertEquals("Terminal in $project", tab.tooltip)

        robot.interact { mainWindow.dispose() }
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)
        assertEquals(0, fileExplorer.root?.children?.size ?: 0)
    }

    @Test
    fun `offers Docker Sandbox - Claude Code and Environment, disabled without a folder`(robot: FxRobot) {
        val items = sandboxItems(robot)

        assertEquals(listOf("Claude Code", "Environment"), items.map { it.text })
        assertEquals(listOf(true, true), items.map { it.isDisable })
    }

    @Test
    fun `Environment is enabled below the home folder once the folder has an sbxenv yaml`(robot: FxRobot) {
        val folder = home.resolve("work/project").createDirectories()
        robot.interact { projectService.open(folder) }
        assertEquals(listOf(false, true), sandboxItems(robot).map { it.isDisable })

        folder.resolve("sbxenv.yaml").createFile()
        robot.interact { menu(robot, "Docker Sandbox").onShowing.handle(null) }

        assertEquals(listOf(false, false), sandboxItems(robot).map { it.isDisable })
    }

    @Test
    fun `starting a sandbox where it may not run shows an error and opens no tab`(robot: FxRobot) {
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        robot.interact { projectService.open(home) }

        // Fired directly: the menu would not offer it, the start is checked again.
        robot.interact { sandboxItems(robot).first().fire() }

        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("Claude Code cannot be started. Docker sandboxes run only"), errors[0])
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)
    }

    @Test
    fun `a terminal or sandbox whose shell cannot be started shows an error and opens no tab`(robot: FxRobot) {
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        val folder = home.resolve("work/project").createDirectories()
        robot.interact { projectService.open(folder) }
        val shell = home.resolve("no-shell")
        shellEnvironment["SHELL"] = shell.toString()

        robot.interact {
            menu(robot, "Terminal").items.single().fire()
            sandboxItems(robot).first().fire()
        }

        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors[0].startsWith("The terminal cannot be started. $shell could not be started in $folder ("))
        assertTrue(errors[1].startsWith("Claude Code cannot be started. $shell could not be started in $folder ("))
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)
    }

    @Test
    fun `the Docker Sandbox menu is disabled without sbx, and until sbx has been found`(robot: FxRobot) {
        assertFalse(menu(robot, "Docker Sandbox").isDisable)

        val missing = otherWindowSandboxMenu(robot, CompletableFuture.completedFuture(false))
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(missing.isDisable)

        val sbxInstalled = CompletableFuture<Boolean>()
        val menu = otherWindowSandboxMenu(robot, sbxInstalled)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(menu.isDisable)

        sbxInstalled.complete(true)
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(menu.isDisable)
        assertEquals(listOf(false, true), menu.items.map { it.isDisable })
    }

    @Test
    fun `Tools - Coloring opens one Coloring tab, and show opens it again once it is closed`(robot: FxRobot) {
        val menus =
            robot
                .lookup(".menu-bar")
                .queryAs(MenuBar::class.java)
                .menus
                .map { it.text }
        assertEquals(listOf("Docker Sandbox", "Tools"), menus.takeLast(2))

        val coloringItem = menu(robot, "Tools").items.single { it.text == "Coloring" }
        robot.interact { coloringItem.fire() }
        robot.interact { coloringItem.fire() }

        assertEquals(listOf("Coloring"), tabTitles(robot))
        assertEquals("Color tools", checkNotNull(editorArea.selectedTab).tooltip)

        robot.interact { editorArea.closeAll() }
        assertEquals(emptyList(), tabTitles(robot))
        val red = CssColor.fromRgb8(255, 0, 0)
        robot.interact { colorings.single().show(red) }

        assertEquals(listOf("Coloring"), tabTitles(robot))
        assertEquals("Color tools", checkNotNull(editorArea.selectedTab).tooltip)
    }

    @Test
    fun `clicking the color swatch in the gutter of a style file opens the Coloring tab`(robot: FxRobot) {
        val file = project.resolve("main.css").apply { writeText("a { color: #ff0000; }\n") }
        robot.interact { editorArea.open(file) }
        eventually(robot) { assertEquals(1, robot.lookup(".color-gutter-swatch").queryAllAs(Node::class.java).size) }
        assertEquals(listOf("main.css"), tabTitles(robot))

        val swatch = robot.lookup(".color-gutter-swatch").queryAs(Node::class.java)
        robot.interact {
            Event.fireEvent(
                swatch,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    null,
                ),
            )
        }

        assertEquals(listOf("main.css", "Coloring"), tabTitles(robot))
        assertEquals("Color tools", checkNotNull(editorArea.selectedTab).tooltip)
    }

    @Test
    fun `Tools - Codecs opens one Codecs tab`(robot: FxRobot) {
        val codecsItem = menu(robot, "Tools").items.single { it.text == "Codecs" }
        robot.interact { codecsItem.fire() }
        robot.interact { codecsItem.fire() }

        assertEquals(listOf("Codecs"), tabTitles(robot))
        assertEquals("Encode and decode text", checkNotNull(editorArea.selectedTab).tooltip)
    }

    @Test
    fun `Open Selection in Codecs in a code editor's context menu opens the Codecs tab with the selection`(
        robot: FxRobot,
    ) {
        val file = project.resolve("notes.txt").apply { writeText("aGVsbG8gd29ybGQ=\n") }
        robot.interact { editorArea.open(file) }
        eventually(robot) { assertEquals(1, robot.lookup(".code-area").queryAllAs(CodeArea::class.java).size) }
        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)

        val item =
            WaitForAsyncUtils
                .asyncFx<MenuItem> {
                    area.selectRange(0, 16)
                    val menu = checkNotNull(area.contextMenu)
                    val at = area.localToScreen(5.0, 5.0)
                    menu.show(area, at.x, at.y)
                    menu.items.single { it.text == "Open Selection in Codecs" }.also { menu.hide() }
                }.get()
        assertFalse(item.isDisable)
        robot.interact { item.fire() }

        assertEquals(listOf("notes.txt", "Codecs"), tabTitles(robot))
        val text = robot.lookup(".text-area").queryAllAs(TextArea::class.java).first { it.promptText == "Text" }
        assertEquals("hello world", WaitForAsyncUtils.asyncFx<String> { text.text }.get())
    }
}
