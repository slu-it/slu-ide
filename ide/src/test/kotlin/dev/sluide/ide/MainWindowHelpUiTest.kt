package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.ide.help.WindowHelp
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UI tests of the main window's Help menu (ADR 0127). Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowHelpUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private fun help(robot: FxRobot) = menu(robot, "Help")

    @Test
    fun `Help is the last menu with Capture, Reveal, a separator and About`(robot: FxRobot) {
        val bar = robot.lookup(".menu-bar").queryAs(MenuBar::class.java)
        assertEquals("Help", bar.menus.last().text)
        val items = help(robot).items
        assertEquals(listOf("Capture Diagnostics", "Reveal Diagnostics Folder"), items.take(2).map { it.text })
        assertTrue(items[2] is SeparatorMenuItem)
        assertEquals("About Thoreau", items[3].text)
        assertEquals(
            KeyCodeCombination(
                KeyCode.D,
                KeyCombination.SHORTCUT_DOWN,
                KeyCombination.ALT_DOWN,
                KeyCombination.SHIFT_DOWN,
            ),
            items[0].accelerator,
        )
    }

    @Test
    fun `Capture Diagnostics captures with the window's tasks and opens the report in a tab`(robot: FxRobot) {
        val report = project.resolve("report.txt").createFile()
        var info: List<Pair<String, String>>? = null
        var tasks: BackgroundTasks? = null
        mainWindow.help.capture = { i, _, t, done, _ ->
            info = i
            tasks = t
            done(report)
        }
        robot.interact { help(robot).items[0].fire() }
        assertTrue(info.orEmpty().isNotEmpty())
        assertSame(backgroundTasks.last(), tasks)
        assertContentEquals(listOf(report), mainWindow.openPaths)
    }

    @Test
    fun `a failed capture shows the error`(robot: FxRobot) {
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        mainWindow.help.capture = { _, _, _, _, failed -> failed(IllegalStateException("no disk")) }
        robot.interact { help(robot).items[0].fire() }
        assertEquals(listOf("Could not capture diagnostics: no disk"), errors)
    }

    @Test
    fun `Capture Diagnostics is disabled while a capture runs`(robot: FxRobot) {
        val capturing = SimpleBooleanProperty(false)
        lateinit var item: MenuItem
        robot.interact {
            val menu =
                WindowHelp(
                    project,
                    capturing,
                    { _, _, _, _, _ -> },
                    BackgroundTasks(),
                    {},
                    { _, _ -> },
                ).helpMenu(stage)
            item = menu.items[0]
        }
        assertFalse(item.isDisable)
        robot.interact { capturing.set(true) }
        assertTrue(item.isDisable)
        robot.interact { capturing.set(false) }
        assertFalse(item.isDisable)
    }

    @Test
    fun `Reveal Diagnostics Folder creates the folder and opens it`(robot: FxRobot) {
        val folder = project.resolve("diagnostics")
        var opened: Path? = null
        val window =
            WindowHelp(folder, SimpleBooleanProperty(false), { _, _, _, _, _ -> }, BackgroundTasks(), {}, { _, _ -> })
        window.openFolder = { opened = it }
        robot.interact { window.helpMenu(stage).items[1].fire() }
        assertEquals(folder, opened)
        assertTrue(
            java.nio.file.Files
                .isDirectory(folder),
        )
    }

    @Test
    fun `About Thoreau shows the about dialog`(robot: FxRobot) {
        var shownFor: Stage? = null
        mainWindow.help.showAbout = { shownFor = it }
        robot.interact { help(robot).items[3].fire() }
        assertEquals(stage, shownFor)
    }

    @Test
    fun `a capture that ends after the window was disposed opens nothing and shows no error`(robot: FxRobot) {
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        var done: (Path) -> Unit = {}
        var failed: (Throwable) -> Unit = {}
        mainWindow.help.capture = { _, _, _, d, f ->
            done = d
            failed = f
        }
        robot.interact { help(robot).items[0].fire() }
        robot.interact {
            mainWindow.dispose()
            done(project.resolve("report.txt").createFile())
            failed(IllegalStateException("late"))
        }
        assertTrue(mainWindow.openPaths.isEmpty())
        assertTrue(errors.isEmpty())
    }
}
