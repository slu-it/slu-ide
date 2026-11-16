package dev.sluide.ide

import dev.sluide.settings.ProjectSettingsPage
import dev.sluide.settings.SettingsDialog
import javafx.scene.control.Label
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wiring of File → Project Settings… in the main window; the menu's order and keys are pinned in
 * `MainWindowProjectUiTest`, the dialog's own behavior in `SettingsUiTest`. Needs a display, see the `uiTest` task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowProjectSettingsUiTest : MainWindowUiFixture() {
    private class Page(
        override val path: List<String>,
    ) : ProjectSettingsPage {
        val loaded = mutableListOf<Path>()
        override val node by lazy { Label(path.joinToString(" › ")) }

        override fun load(folder: Path) {
            loaded.add(folder)
        }

        override fun apply(folder: Path) {}
    }

    private val pageX = Page(listOf("X"))
    private val pageY = Page(listOf("Y"))

    @Start
    fun start(stage: Stage) {
        projectSettingsPages = listOf(pageY, pageX)
        startView(stage)
    }

    @Test
    fun `the menu item is enabled once the window has a folder`(robot: FxRobot) {
        val item = fileMenuItem(robot, "Project Settings…")
        assertTrue(item.isDisable)

        robot.interact { projectService.open(project) }

        assertFalse(item.isDisable)
    }

    @Test
    fun `the menu item opens a dialog for the window's folder, owned by the stage`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        var dialog: SettingsDialog? = null
        mainWindow.showProjectSettings = { dialog = it }

        robot.interact { fileMenuItem(robot, "Project Settings…").fire() }

        assertEquals(stage, dialog?.owner)
        assertEquals(listOf(project), pageX.loaded)
    }

    @Test
    fun `show passes the path through to the dialog`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        var dialog: SettingsDialog? = null
        mainWindow.showProjectSettings = { dialog = it }

        robot.interact { projectSettings.show(listOf("Y")) }

        assertEquals(listOf("Y"), dialog?.shownPage?.path)
    }

    @Test
    fun `the window registers its opener for its folder and shows its dialog through it`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        var dialog: SettingsDialog? = null
        mainWindow.showProjectSettings = { dialog = it }

        var shown = false
        robot.interact { shown = projectSettingsOpeners.show(project, listOf("Y")) }

        assertTrue(shown)
        assertEquals(listOf("Y"), dialog?.shownPage?.path)
        var other = true
        robot.interact { other = projectSettingsOpeners.show(project.resolve("other"), null) }
        assertFalse(other)
    }

    @Test
    fun `disposing the window ends its registration`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        // Without the registration ending the show below would open the real, blocking dialog.
        mainWindow.showProjectSettings = {}

        var shown = true
        robot.interact {
            mainWindow.dispose()
            shown = projectSettingsOpeners.show(project, null)
        }

        assertFalse(shown)
    }

    @Test
    fun `show does nothing without a folder`(robot: FxRobot) {
        var opened = 0
        mainWindow.showProjectSettings = { opened++ }

        robot.interact { projectSettings.show(listOf("X")) }

        assertEquals(0, opened)
    }
}
