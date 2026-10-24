package dev.sluide.ide

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
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
    fun `a new terminal opens as a tab in the project folder, disposing the window ends it`(robot: FxRobot) {
        robot.interact { projectService.open(project) }

        robot.interact { menu(robot, "Terminal").items.single().fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("Terminal 1"), tabTitles(robot))
        val tab = checkNotNull(editorArea.selectedTab)
        assertEquals("Terminal in $project", tab.tooltip)

        robot.interact { mainWindow.dispose() }
        WaitForAsyncUtils.waitForFxEvents()
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
}
