package dev.sluide.speech.ui

import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.fxmisc.richtext.GenericStyledArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.Callable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wiring of the Read Aloud view to its model: every toolbar control, key and the editor, with a fake host (no
 * engine, no sound line). The rules are in [ReadAloudModelTest]. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReadAloudViewUiTest {
    private val host = FakeHost().also { it.onFx = javafx.application.Platform::runLater }
    private val model = ReadAloudModel(host)
    private lateinit var view: ReadAloudView

    @Start
    fun start(stage: Stage) {
        view = ReadAloudView(model, javafx.beans.property.SimpleStringProperty(""))
        stage.scene = Scene(view.node as BorderPane, 700.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get()

    private fun node(id: String): Node = fx { checkNotNull(view.node.lookup("#$id")) { "no #$id" } }

    @Suppress("UNCHECKED_CAST")
    private val area get() =
        fx {
            view.node.lookup(".styled-text-area") as GenericStyledArea<Any?, Any?, Collection<String>>
        }

    private fun key(
        code: KeyCode,
        shortcut: Boolean = false,
    ) = fx {
        val target = area
        val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, false)
        Event.fireEvent(target, event)
    }

    private fun speaking(robot: FxRobot) {
        robot.interact { model.read("One. Two. Three.", markdown = false) }
        host.reading.emit(ReadingState.SPEAKING)
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `the play button reads from the caret, then pauses and resumes, and the stop button stops`(robot: FxRobot) {
        robot.interact {
            model.textEdited("One. Two. Three.")
            (node("speech-play") as Button).fire()
        }
        assertEquals(1, host.readings.size)
        host.reading.emit(ReadingState.SPEAKING)
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { (node("speech-play") as Button).fire() }
        assertEquals(listOf("start", "pause"), host.reading.calls)
        host.reading.emit(ReadingState.PAUSED)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("Paused", fx { (node("speech-status") as Label).text })

        robot.interact { (node("speech-stop") as Button).fire() }
        assertTrue("stop" in host.reading.calls)
        assertTrue(fx { !(node("speech-play") as Button).isDisabled })
        assertTrue(fx { (node("speech-stop") as Button).isDisabled })
    }

    @Test
    fun `the voice picker lists headings and voices, choosing one and a speed go to the model`(robot: FxRobot) {
        @Suppress("UNCHECKED_CAST")
        val voices = node("speech-voice") as ComboBox<VoiceItem>
        assertEquals(
            listOf("American English", "Heart", "British English", "Emma"),
            fx { voices.items.map { (it as? VoiceItem.Heading)?.text ?: (it as VoiceItem.Entry).voice.name } },
        )

        robot.interact { voices.value = voices.items.last() }
        robot.interact { (node("speech-speed") as MenuButton).items[4].fire() }

        assertEquals(listOf("bf_emma" to 1.0, "bf_emma" to 1.5), host.saved)
        assertEquals("1.5×", fx { (node("speech-speed") as MenuButton).text })
    }

    @Test
    fun `Shortcut+Enter plays while editable, Space and Escape pause and stop while reading`(robot: FxRobot) {
        robot.interact { model.textEdited("One. Two. Three.") }
        key(KeyCode.SPACE)
        assertTrue(host.readings.isEmpty(), "Space does nothing to the reading while the text is editable")
        key(KeyCode.ENTER, shortcut = true)
        assertEquals(1, host.readings.size)
        host.reading.emit(ReadingState.SPEAKING)
        WaitForAsyncUtils.waitForFxEvents()

        key(KeyCode.SPACE)
        assertEquals(listOf("start", "pause"), host.reading.calls)
        key(KeyCode.ESCAPE)
        assertTrue("stop" in host.reading.calls)
        assertTrue(fx { (node("speech-stop") as Button).isDisabled })
    }

    @Test
    fun `a double-click on the locked text jumps to the sentence at the caret`(robot: FxRobot) {
        speaking(robot)
        robot.interact { area.moveTo(11) }

        robot.interact {
            Event.fireEvent(
                area,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    MouseButton.PRIMARY,
                    2,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }

        assertEquals(2, host.readings.size)
        assertEquals(2, host.reading.startChunk)
    }

    @Test
    fun `the text is locked while reading and the overlay and text come from the model`(robot: FxRobot) {
        robot.interact { model.read("One. Two. Three.", markdown = false) }
        assertEquals("One. Two. Three.", fx { area.text })
        assertFalse(fx { area.isEditable })

        host.reading.emit(ReadingState.SPEAKING)
        host.reading.position = ReadingPosition(1, 5, 9, 5, 8)
        robot.interact { host.pollTick?.run() }

        assertTrue(fx { "speech-sentence" in area.getStyleOfChar(6) })
        assertTrue(fx { "speech-word" in area.getStyleOfChar(6) })
        assertFalse(fx { "speech-sentence" in area.getStyleOfChar(1) })

        host.reading.emit(ReadingState.FINISHED)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(fx { area.isEditable })
        assertFalse(fx { "speech-sentence" in area.getStyleOfChar(6) })
    }

    @Test
    fun `the notice offers Download and Check Again and follows the download`(robot: FxRobot) {
        host.problems =
            listOf(
                MODEL_MISSING,
                dev.sluide.speech.engine.SetupProblem
                    .ToolMissing("espeak-ng", "Install it"),
            )
        robot.interact { model.read("Hi.", markdown = false) }
        assertTrue(fx { view.node.lookup(".speech-notice").isVisible })

        robot.interact { (node("speech-download") as Button).fire() }
        assertEquals(listOf("download FP32"), host.events)

        robot.interact { host.state.set(InstallState.Downloading(0.4)) }
        assertEquals(true, fx { node("speech-download-progress").isVisible })
        robot.interact { (node("speech-download-cancel") as Button).fire() }
        assertEquals(listOf("download FP32", "cancel FP32"), host.events)

        host.problems =
            listOf(
                dev.sluide.speech.engine.SetupProblem
                    .ToolMissing("espeak-ng", "Install it"),
            )
        robot.interact { host.state.set(InstallState.Installed) }
        assertEquals(
            1,
            fx { view.node.lookup(".speech-notice").let { (it as javafx.scene.layout.VBox).children.size } },
        )

        host.problems = emptyList()
        robot.interact { (node("speech-check-again") as Button).fire() }
        assertFalse(fx { view.node.lookup(".speech-notice").isVisible })
        assertTrue(host.readings.isEmpty(), "nothing starts from the notice")
    }

    @Test
    fun `text typed into the editor is what Play reads`(robot: FxRobot) {
        robot.interact { area.replaceText("Typed text.") }

        robot.interact { (node("speech-play") as Button).fire() }

        assertEquals(
            "Typed text.",
            host.reading.chunks
                .single()
                .text,
        )
    }

    @Test
    fun `Play and Shortcut+Enter start at the sentence of the caret`(robot: FxRobot) {
        robot.interact {
            area.replaceText("One. Two. Three.")
            area.moveTo(6)
            (node("speech-play") as Button).fire()
        }
        assertEquals(1, host.reading.startChunk)
        robot.interact { model.stop() }

        robot.interact { area.moveTo(11) }
        key(KeyCode.ENTER, shortcut = true)

        assertEquals(2, host.reading.startChunk)
    }

    @Test
    fun `a double-click on the last word of a sentence jumps to that sentence`(robot: FxRobot) {
        val markdown = "# Title\n\nHello"
        robot.interact { model.read(markdown, markdown = true) }
        host.reading.emit(ReadingState.STOPPED)
        // Reading starts in "Hello", the second chunk; the double-click on "Title" must go back to the first.
        robot.interact { model.play(markdown.indexOf("Hello")) }
        host.reading.emit(ReadingState.SPEAKING)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, host.reading.startChunk)
        assertEquals(2, host.readings.size)
        // What the double-click does: it selects the word and leaves the caret at its end.
        robot.interact { area.selectRange(2, 7) }

        robot.interact {
            Event.fireEvent(
                area,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    MouseButton.PRIMARY,
                    2,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }

        assertEquals(3, host.readings.size)
        assertEquals(0, host.reading.startChunk)
    }

    @Test
    fun `the tab shows the reading as its activity`(robot: FxRobot) {
        val tab = ReadAloudTab(model, view)
        assertEquals(ViewActivity.NONE, fx { tab.activity.value })

        speaking(robot)
        assertEquals(ViewActivity.RUNNING, fx { tab.activity.value })

        robot.interact { model.stop() }
        assertEquals(ViewActivity.NONE, fx { tab.activity.value })
    }

    @Test
    fun `a model already installed shows that and checks again, a tool row names the tool`(robot: FxRobot) {
        host.problems = listOf(MODEL_MISSING, SetupProblem.ToolMissing("espeak-ng", "Install it with `brew`."))
        host.state.set(InstallState.Installed)

        robot.interact { model.read("Hi.", markdown = false) }

        val texts = fx { (view.node.lookup(".speech-notice") as VBox).lookupAll(".label").map { (it as Label).text } }
        assertEquals(listOf("Kokoro voice model installed.", "espeak-ng not found: Install it with brew."), texts)
        assertEquals(2, host.checks, "the start's check and the one the installed row asked for")
    }
}
