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
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
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

    private fun open(robot: FxRobot): FileView {
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
                DefaultTextFileViewer().open(dir.resolve("a.txt").apply { writeText("One. Two. Three.\n") })
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

    private companion object {
        val SPEECH_CSS: String =
            checkNotNull(EditorReadingUiTest::class.java.getResource("/dev/sluide/speech/speech.css")).toExternalForm()
    }
}
