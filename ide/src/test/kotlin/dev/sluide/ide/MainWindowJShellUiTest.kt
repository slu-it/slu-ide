package dev.sluide.ide

import dev.sluide.jshell.FakeAgent
import dev.sluide.jshell.JShellSession
import dev.sluide.jshell.Recordings
import javafx.scene.control.MenuItem
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI test of "Evaluate Selection in JShell" in the main window (ADR 0191), on the module's fake agent that replays
 * `multi-snippet.jsonl`: no JVM starts. The other cases of the item (extensions, selection) are the unit tests of
 * `JShellEditorExtensions`. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowJShellUiTest : MainWindowUiFixture() {
    private val agent = FakeAgent.of("multi-snippet")
    private val sessionsStarted = AtomicInteger()

    // the document of the recording's Run All request (its second line): rangeStart 0, rangeEnd 213
    private val document = Recordings.load("multi-snippet")[1].message["text"] as String

    @Start
    fun start(stage: Stage) {
        jshellStartSession = { jdk, onEvent ->
            sessionsStarted.incrementAndGet()
            JShellSession.start(jdk, onEvent, { agent })
        }
        startView(stage)
    }

    private fun openJava(robot: FxRobot): CodeArea {
        val file = project.resolve("A.java").apply { writeText(document) }
        robot.interact { editorArea.open(file) }
        eventually(robot) { assertEquals(1, robot.lookup(".code-area").queryAllAs(CodeArea::class.java).size) }
        return robot.lookup(".code-area").queryAs(CodeArea::class.java)
    }

    /** The item "Evaluate Selection in JShell" of the context menu, with all the text selected. */
    private fun item(area: CodeArea): MenuItem =
        WaitForAsyncUtils
            .asyncFx<MenuItem> {
                area.selectAll()
                val menu = checkNotNull(area.contextMenu)
                val at = area.localToScreen(5.0, 5.0)
                menu.show(area, at.x, at.y)
                menu.items.single { it.text == "Evaluate Selection in JShell" }.also { menu.hide() }
            }.get()

    private fun openProject(robot: FxRobot) {
        robot.interact { projectService.open(project) }
    }

    @Test
    fun `Evaluate Selection in JShell opens selection jsh with the selected text and runs it all`(robot: FxRobot) {
        val jdk = Files.createDirectories(project.resolve("jdk"))
        Files.createDirectories(jdk.resolve("bin")).resolve("java").writeText("")
        jdk.resolve("release").writeText("JAVA_VERSION=\"25.0.5\"\n")
        robot.interact {
            projectService.open(project)
            javaProjectSettings.setJdk(project, jdk)
        }
        val item = item(openJava(robot))
        assertFalse(item.isDisable)

        robot.interact { item.fire() }

        val scratch = scratchFiles.folder.resolve("selection.jsh")
        assertEquals(document, Files.readString(scratch))
        assertEquals(listOf("A.java", "selection.jsh"), tabTitles(robot))
        assertEquals(scratch, editorArea.selectedPath)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!agent.awaitsClose() && System.nanoTime() < deadline) WaitForAsyncUtils.waitForFxEvents()
        // the fake checks the request against the recording (the Run All of the whole text) and answers it
        assertEquals(emptyList(), agent.problems.toList())
        assertTrue(agent.awaitsClose(), "the Run All request did not reach the agent")
    }

    @Test
    fun `Evaluate Selection in JShell shows the error and runs nothing when the scratch cannot be created`(
        robot: FxRobot,
    ) {
        openProject(robot)
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        val item = item(openJava(robot))
        Files.delete(scratchFiles.folder)
        scratchFiles.folder.createFile()

        robot.interact { item.fire() }

        assertEquals(listOf("The scratch file cannot be created. ${scratchFiles.folder} is in the way."), errors)
        assertEquals(listOf("A.java"), tabTitles(robot))
        assertEquals(0, sessionsStarted.get())
    }
}
