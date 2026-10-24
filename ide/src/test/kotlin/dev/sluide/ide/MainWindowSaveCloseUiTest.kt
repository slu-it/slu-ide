package dev.sluide.ide

import dev.sluide.editorarea.SaveChoice
import javafx.event.ActionEvent
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI tests of the main window: saving and closing the window. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowSaveCloseUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `the save shortcut works in the editor, and in a terminal Ctrl+S stays the shell's`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
        }
        robot.interact { pressShortcutS(shownArea(robot)) }
        assertEquals("a1", a.readText())

        var saves = 0
        var savesAll = 0
        fileMenuItem(robot, "Save").addEventHandler(ActionEvent.ACTION) { saves++ }
        fileMenuItem(robot, "Save All").addEventHandler(ActionEvent.ACTION) { savesAll++ }
        robot.interact {
            (checkNotNull(editorArea.selectedTab).content.lookup(".code-area") as CodeArea).appendText("2")
            editorArea.open(terminals.open(project, "Job", "sleep 300"))
        }
        val terminal = checkNotNull(editorArea.selectedTab).content
        robot.interact { pressShortcutS(terminal) }
        // Cmd+S on macOS is no key for the shell; Ctrl+S elsewhere is (it stops the output).
        if (!System.getProperty("os.name").startsWith("Mac")) assertEquals(0, saves)

        robot.interact { pressShortcutS(terminal, alt = true) }
        assertEquals(1, savesAll)
        assertEquals("a12", a.readText())
    }

    @Test
    fun `the close shortcut closes a file tab, and a terminal tab stays open`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
        }
        assertFalse(fileMenuItem(robot, "Close Tab").isDisable)
        robot.interact { pressShortcut(shownArea(robot), KeyCode.W) }
        assertEquals(emptyList(), tabTitles(robot))

        robot.interact { editorArea.open(terminals.open(project, "Job", "sleep 300")) }
        val terminal = checkNotNull(editorArea.selectedTab).content
        assertTrue(fileMenuItem(robot, "Close Tab").isDisable)
        robot.interact { pressShortcut(terminal, KeyCode.W) }
        // Outside the terminal the key reaches the menu item, which is disabled.
        robot.interact { pressShortcut(stage.scene.root, KeyCode.W) }
        assertTrue(fileMenuItem(robot, "Close Tab").isDisable)
        assertEquals(1, tabTitles(robot).size)
    }

    @Test
    fun `closing the window asks once while tabs run something, Cancel keeps it open`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        robot.interact { editorArea.open(terminals.open(project, "Job 1", "sleep 300")) }
        robot.interact { editorArea.open(terminals.open(project, "Job 2", "sleep 300")) }
        val asked = mutableListOf<List<String>>()
        var answer = false
        mainWindow.confirmCloseWindow = { _, warnings ->
            asked += warnings
            answer
        }

        robot.interact { requestClose() }
        assertEquals(listOf(listOf("Job 1 is still running: sleep 300", "Job 2 is still running: sleep 300")), asked)
        assertTrue(stage.isShowing)
        assertEquals(2, terminals.openCount)

        answer = true
        robot.interact { requestClose() }
        assertEquals(2, asked.size)
        assertFalse(stage.isShowing)
    }

    @Test
    fun `closing the window asks to save edited files first, Cancel keeps it open`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
            editorArea.open(terminals.open(project, "Job 1", "sleep 300"))
        }
        val asked = mutableListOf<String>()
        var answer = SaveChoice.CANCEL
        editorArea.confirmSave = { files ->
            asked += "save $files"
            answer
        }
        mainWindow.confirmCloseWindow = { _, _ ->
            asked += "close"
            true
        }

        robot.interact { requestClose() }
        assertEquals(listOf("save [a.txt]"), asked)
        assertTrue(stage.isShowing)

        answer = SaveChoice.SAVE
        robot.interact { requestClose() }
        assertEquals(listOf("save [a.txt]", "save [a.txt]", "close"), asked)
        assertEquals("a1", a.readText())
        assertFalse(stage.isShowing)
    }

    @Test
    fun `a checkout from the git log first asks to save the edited files of the repository`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
        }
        var answer = SaveChoice.CANCEL
        val asked = mutableListOf<Pair<List<String>, String>>()
        editorArea.confirmSaveBeforeGitChange = { files, action ->
            asked += files to action
            answer
        }

        // What the editor area keeps and saves is EditorAreaSavingUiTest's.
        robot.interact { assertFalse(gitLogView.beforeFileChange("Check Out")) }
        answer = SaveChoice.DISCARD
        robot.interact { assertTrue(gitLogView.beforeFileChange("Cherry-Pick")) }
        assertEquals(listOf(listOf("a.txt") to "Check Out", listOf("a.txt") to "Cherry-Pick"), asked)
    }

    @Test
    fun `a checkout in a folder without a repository does not ask to save`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
        }
        editorArea.confirmSaveBeforeGitChange = { _, _ -> error("must not ask") }

        robot.interact { assertTrue(gitLogView.beforeFileChange("Check Out")) }
    }

    @Test
    fun `closing the window with Don't Save leaves the files as they are`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
        }
        editorArea.confirmSave = { SaveChoice.DISCARD }
        mainWindow.confirmCloseWindow = { _, _ -> error("nothing runs") }

        robot.interact { requestClose() }

        assertFalse(stage.isShowing)
        assertEquals("a", a.readText())
    }

    @Test
    fun `closing the window does not ask when nothing runs`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        mainWindow.confirmCloseWindow = { _, _ -> error("must not ask") }

        robot.interact { requestClose() }

        assertFalse(stage.isShowing)
    }
}
