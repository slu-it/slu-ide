package dev.sluide.ide.codeintelligence

import dev.sluide.ide.MainWindowUiFixture
import dev.sluide.jshell.FakeAgent
import dev.sluide.jshell.JShellSession
import javafx.event.Event
import javafx.scene.control.Button
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The completion of `.jsh` editors in the main window (ADR 0145 amendment): `CodeIntelligenceWiring` registers
 * `JShellCompletionSource`, which asks the file's JShell view, on the module's fake agent replaying
 * `complete-and-run.jsonl` (no JVM starts). What the popup and the model do is code-intelligence's. Needs a
 * display: key routing, focus and the popup window exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowJShellCompletionUiTest : MainWindowUiFixture() {
    // the recording's document: completion at its end, then a run at its end
    private val document = "\"abc\".len"
    private var agent = FakeAgent.of("complete-and-run")
    private val sessionsStarted = AtomicInteger()

    @Start
    fun start(stage: Stage) {
        jshellStartSession = { jdk, onEvent ->
            sessionsStarted.incrementAndGet()
            JShellSession.start(jdk, onEvent, { agent })
        }
        startView(stage)
    }

    /** The project with a JDK folder as its JDK setting, which the JShell view accepts. */
    private fun openProjectWithJdk(robot: FxRobot) {
        val jdk = Files.createDirectories(project.resolve("jdk"))
        Files.createDirectories(jdk.resolve("bin")).resolve("java").writeText("")
        jdk.resolve("release").writeText("JAVA_VERSION=\"25.0.5\"\n")
        robot.interact {
            projectService.open(project)
            javaProjectSettings.setJdk(project, jdk)
        }
    }

    /** Opens [name] with [document] and puts the focus and the caret at the end of its text. */
    private fun open(
        robot: FxRobot,
        name: String,
    ): CodeArea {
        val file = project.resolve(name).apply { writeText(document) }
        robot.interact { editorArea.open(file) }
        eventually(robot) { assertEquals(1, robot.lookup(".code-area").queryAllAs(CodeArea::class.java).size) }
        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        robot.interact {
            area.requestFocus()
            area.moveTo(document.length)
        }
        eventually(robot) { assertTrue(area.isFocused, "the test needs the focus in the source") }
        return area
    }

    /** Opens the `.jsh` file and waits until its view found the JDK (Run is enabled then). */
    private fun openJsh(robot: FxRobot): CodeArea {
        openProjectWithJdk(robot)
        val area = open(robot, "a.jsh")
        eventually(robot) { assertTrue(!robot.lookup(".jshell-run").queryAs(Button::class.java).isDisable) }
        return area
    }

    private fun press(
        robot: FxRobot,
        area: CodeArea,
        code: KeyCode,
        control: Boolean = false,
    ) = robot.interact {
        Event.fireEvent(area, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false))
    }

    private fun completionPopups(): List<Popup> =
        WaitForAsyncUtils
            .asyncFx<List<Popup>> {
                Window.getWindows().filterIsInstance<Popup>().filter { popup ->
                    popup.isShowing && popup.content.any { "caret-popup" in it.styleClass }
                }
            }.get()

    @Suppress("UNCHECKED_CAST")
    private fun shownLabels(): List<String>? =
        completionPopups().singleOrNull()?.let { popup ->
            WaitForAsyncUtils
                .asyncFx<List<String>> {
                    val list = popup.content.single().lookup(".caret-popup-list") as ListView<Any>
                    list.items.map { it.toString() }
                }.get()
        }

    /** Waits until the agent answered every recorded request; [agent] then waits for `close`. */
    private fun awaitAnswered() {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!agent.awaitsClose() && System.nanoTime() < deadline) WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), agent.problems.toList())
        assertTrue(agent.awaitsClose(), "the agent did not get every recorded request")
    }

    @Test
    fun `Ctrl+Space in a jsh editor shows JShell's items, and Enter inserts the chosen one`(robot: FxRobot) {
        // the completion only: ready, complete, then close
        agent = FakeAgent.of("complete-and-run", only = listOf(1, 2, 3, 8, 9))
        val area = openJsh(robot)

        press(robot, area, KeyCode.SPACE, control = true)
        eventually(robot) { assertEquals(1, shownLabels()?.size) }
        assertTrue(shownLabels().orEmpty().single().contains("length()"))
        press(robot, area, KeyCode.ENTER)

        assertEquals("\"abc\".length()", WaitForAsyncUtils.asyncFx<String> { area.text }.get())
        assertEquals(emptyList(), completionPopups())
        awaitAnswered()
        assertEquals(1, sessionsStarted.get())
    }

    @Test
    fun `Shortcut+Enter with the popup open runs the snippet and inserts nothing`(robot: FxRobot) {
        val area = openJsh(robot)
        press(robot, area, KeyCode.SPACE, control = true)
        eventually(robot) { assertEquals(1, shownLabels()?.size) }

        robot.interact { pressShortcut(area, KeyCode.ENTER) }

        // the fake checks the run's request against the recording (the evaluation at the caret) and answers it
        awaitAnswered()
        assertEquals(document, WaitForAsyncUtils.asyncFx<String> { area.text }.get())
        assertEquals(1, sessionsStarted.get())
    }

    @Test
    fun `Ctrl+Space in a txt editor of the same window is not taken`(robot: FxRobot) {
        openProjectWithJdk(robot)
        val area = open(robot, "a.txt")
        var reached = 0
        // a key a binding took never reaches the handlers of the text area
        robot.interact { area.addEventHandler(KeyEvent.KEY_PRESSED) { if (it.code == KeyCode.SPACE) reached++ } }

        press(robot, area, KeyCode.SPACE, control = true)

        assertEquals(1, reached, "no completion took the key")
        assertEquals(emptyList(), completionPopups())
        assertEquals(0, sessionsStarted.get())
    }
}
