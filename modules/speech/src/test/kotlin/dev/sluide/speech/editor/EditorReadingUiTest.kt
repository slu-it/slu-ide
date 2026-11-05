package dev.sluide.speech.editor

import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import dev.sluide.speech.ui.FakeHost
import dev.sluide.speech.ui.ReadAloudModel
import dev.sluide.speech.ui.SENTENCE_STYLE
import dev.sluide.speech.ui.WORD_STYLE
import javafx.application.Platform
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ToggleButton
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.GenericStyledArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wiring of [EditorReading] to a real plain-text code view: the toolbar row, the highlight in the text area and the
 * lock (the rules are `EditorReadingTest`'s). Needs a display: the row, the styled text and editability exist only in
 * a laid-out view.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorReadingUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private val host = FakeHost().also { it.onFx = Platform::runLater }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get()

    private fun open(
        robot: FxRobot,
        text: String = "One. Two. Three.\n",
    ): FileView {
        val extensions =
            CodeEditorExtensions().apply {
                add(
                    ReadAloudExtension({ _, _ -> }) { editor ->
                        val model = ReadAloudModel(host, ownsText = false)
                        EditorReading(
                            model,
                            editor,
                            { play -> ReadAloudEditorControls(model, play, SPEECH_CSS) },
                            SPEECH_CSS,
                        )
                    },
                )
            }
        val services = WindowServices().apply { register(CodeEditorExtensions::class.java, extensions) }
        val view =
            fx {
                DefaultTextFileViewer().open(dir.resolve("a.txt").apply { writeText(text) })
            }
        robot.interact {
            view.host =
                object : FileViewHost {
                    override fun openFile(file: Path) = false

                    override fun openWebPage(uri: URI) {}

                    override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
                }
            container.children.setAll(view.node)
        }
        return view
    }

    @Suppress("UNCHECKED_CAST")
    private fun area(view: FileView) =
        fx { view.node.lookup(".styled-text-area") as GenericStyledArea<Any?, Any?, Collection<String>> }

    @Test
    fun `Play in the toolbar row highlights the first sentence and locks the text until Stop`(robot: FxRobot) {
        val view = open(robot)
        val area = area(view)
        val play = fx { view.node.lookup("#speech-play") as Button }
        assertTrue(fx { area.isEditable })

        robot.interact { play.fire() }
        robot.interact { host.reading.emit(ReadingState.SPEAKING) }
        robot.interact {
            host.reading.position = ReadingPosition(0, 0, 4, null, null)
            host.pollTick?.run()
        }

        assertFalse(fx { area.isEditable })
        val styled = fx { area.getStyleSpans(0, 4).all { "speech-sentence" in it.style } }
        assertTrue(styled)

        robot.interact { (view.node.lookup("#speech-stop") as Button).fire() }

        assertTrue(fx { area.isEditable })
    }

    @Test
    fun `reading follows a sentence on a wrapped line and a double-click on a wrapped line jumps to it`(
        robot: FxRobot,
    ) {
        val text = (0 until 150).joinToString(" ", postfix = "\n") { "Sentence $it runs on with several plain words." }
        val view = open(robot, text)
        val area = area(view)
        robot.interact { (view.node.lookup(".soft-wrap-toggle") as ToggleButton).fire() }
        assertTrue(fx { area.isWrapText }, "wrapping is on")
        assertTrue(fx { area.totalHeightEstimate > area.height * 2 }, "the paragraph is much taller")

        robot.interact { (view.node.lookup("#speech-play") as Button).fire() }
        robot.interact { host.reading.emit(ReadingState.SPEAKING) }
        val chunks = host.reading.chunks

        fun bounds(offset: Int) =
            fx {
                area.getCharacterBoundsOnScreen(offset, offset + 1).orElse(null)?.let(area::screenToLocal)
            }

        val chunk = chunks[chunks.size - 4]
        val word = chunk.start + chunk.text.indexOf("words")
        val wordEnd = word + "words".length
        robot.interact {
            host.reading.position =
                ReadingPosition(chunks.indexOf(chunk), chunk.sentenceStart, chunk.sentenceEnd, word, wordEnd)
            host.pollTick?.run()
        }

        fun styled(
            style: String,
            at: Int,
        ) = fx { style in area.getStyleOfChar(at) }
        assertTrue((chunk.sentenceStart until chunk.sentenceEnd).all { styled(SENTENCE_STYLE, it) })
        assertFalse(styled(SENTENCE_STYLE, chunk.sentenceStart - 1))
        assertFalse(styled(SENTENCE_STYLE, chunk.sentenceEnd))
        assertTrue((word until wordEnd).all { styled(WORD_STYLE, it) })
        assertFalse(styled(WORD_STYLE, word - 1))
        assertFalse(styled(WORD_STYLE, wordEnd))

        // The same tolerance as CodeEditorExtensionsUiTest.inViewport.
        fun inViewport(offset: Int) = bounds(offset)?.let { it.minY >= -3 && it.maxY <= fx { area.height } + 3 } == true
        assertFalse(inViewport(0), "the start of the paragraph is scrolled away")
        assertTrue(inViewport(word), "the spoken word shows")

        // A sentence above it that shows too, on a continuation line: a double-click on it jumps there.
        val target =
            chunks.take(chunks.indexOf(chunk)).last { c -> inViewport(c.start + 3) && bounds(c.start + 3)!!.minX > 0 }
        val local = checkNotNull(bounds(target.start + 3)).let { Point2D(it.centerX, it.centerY) }
        val before = host.readings.size
        // RichTextFX hit-tests the event's point; EditorReading listens for MOUSE_CLICKED with clickCount 2.
        robot.interact {
            for (count in 1..2) {
                listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED).forEach {
                    fireMouse(area, it, local, count)
                }
            }
        }

        assertTrue(host.readings.size > before, "a new reading started")
        assertEquals(chunks.indexOf(target), host.reading.startChunk)
    }

    /** Fires a primary-button mouse event of [type] at the local point [local] of [area]. */
    private fun fireMouse(
        area: Node,
        type: EventType<MouseEvent>,
        local: Point2D,
        count: Int,
    ) {
        val scene = area.localToScene(local)
        val screen = area.localToScreen(local)
        val down = type == MouseEvent.MOUSE_PRESSED
        Event.fireEvent(
            area,
            MouseEvent(
                type,
                scene.x,
                scene.y,
                screen.x,
                screen.y,
                MouseButton.PRIMARY,
                count,
                false,
                false,
                false,
                false,
                down,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    private companion object {
        val SPEECH_CSS: String =
            checkNotNull(EditorReadingUiTest::class.java.getResource("/dev/sluide/speech/speech.css")).toExternalForm()
    }
}
