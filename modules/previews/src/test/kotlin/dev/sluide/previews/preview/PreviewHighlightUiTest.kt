package dev.sluide.previews.preview

import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.WindowServices
import dev.sluide.previews.markdown.MarkdownFileViewer
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wiring of [PreviewHighlightMirror] in a Markdown preview (ADR 0166): the source's overlay layers marked in the
 * page. Its decisions are `PreviewHighlightMirrorTest`'s. Needs a display: the marks exist only in WebKit.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PreviewHighlightUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun awaitLoaded(view: PreviewFileView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun PreviewFileView.script(code: String): Any? = fx { webView.engine.executeScript(code) }

    /** The text the page's mark [name] covers, `null` without one. */
    private fun PreviewFileView.marked(name: String): Any? =
        script(
            "(function () { var h = CSS.highlights.get('mark-$name'), text = null;" +
                " if (h) h.forEach(function (r) { text = r.toString(); }); return text; })()",
        )

    /** The page paints the highlight [name] with its colour: a snapshot row inside the top of the range's first box. */
    private fun assertPaints(
        view: PreviewFileView,
        name: String,
        light: String,
        dark: String,
    ) {
        fun rect(side: String) =
            (
                view.script(
                    "(function () { var r = null; CSS.highlights.get('mark-$name').forEach(function (x) { r = x; });" +
                        " return r.getClientRects()[0].$side; })()",
                ) as Number
            ).toInt()
        val expected = Color.web(if (view.script("document.body.classList.contains('dark')") == true) dark else light)
        val left = rect("left")
        val top = rect("top")

        fun matches(): Int {
            val image = fx { view.webView.snapshot(null, null) }
            return (left + 1..left + 12).count {
                val c = image.pixelReader.getColor(it, top + 1)
                abs(c.red - expected.red) < 0.05 && abs(c.green - expected.green) < 0.05 &&
                    abs(c.blue - expected.blue) < 0.05
            }
        }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { matches() >= 6 }
        assertTrue(matches() >= 6, "$name is painted in $expected")
    }

    /** A host whose window has code editor extensions that collect the editors they extend into [editors]. */
    private fun extending(editors: MutableList<ExtensibleCodeEditor>) =
        object : FileViewHost {
            private val services =
                WindowServices().apply {
                    val extensions =
                        CodeEditorExtensions().apply {
                            add {
                                editors += it
                                null
                            }
                        }
                    register(CodeEditorExtensions::class.java, extensions)
                }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    @Test
    fun `the source's highlight is marked in the preview, again when it renders anew, and revealed alone`(
        robot: FxRobot,
    ) {
        val markdown = "# Hi\n\nSome *spoken* words.\n" + "\nMore.\n".repeat(100) + "\nThe end.\n"
        val file = dir.resolve("README.md").apply { writeText(markdown) }
        // A WebView must be created on the FX thread, as the editor area does.
        val view = fx { MarkdownFileViewer().open(file) as PreviewFileView }
        val editors = mutableListOf<ExtensibleCodeEditor>()
        robot.interact {
            container.children.setAll(view.node)
            view.host = extending(editors)
        }
        awaitLoaded(view)
        val sentence = StyledRange(6, 26, "speech-sentence")

        robot.interact { editors.single().overlay("read-aloud", listOf(sentence, StyledRange(12, 18, "speech-word"))) }

        assertEquals("spoken", view.marked("speech-word"))
        assertEquals("Some spoken words.", view.marked("speech-sentence"))
        assertPaints(view, "speech-word", "#9cc2f5", "#3f6aa8")
        assertPaints(view, "speech-sentence", "#dbe8fb", "#2f4361")

        // Stopped and shown again: a new page.
        val other = dir.resolve("other.html").apply { writeText("x") }
        robot.interact { view.webView.engine.load(other.toUri().toString()) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { view.notice.parent.isVisible } }
        robot.interact { robot.lookup(".preview-notice .button").queryButton().fire() }
        awaitLoaded(view)
        assertEquals("spoken", view.marked("speech-word"))

        robot.interact { editors.single().overlay("read-aloud", emptyList()) }
        assertEquals(null, view.marked("speech-word"))

        val end = markdown.indexOf("end")
        robot.interact {
            view.mode.value = PreviewMode.PREVIEW
            editors.single().overlay("read-aloud", listOf(StyledRange(end - 4, end + 3, "speech-word")))
        }
        assertEquals("The end", view.marked("speech-word"))
        assertTrue((view.script("window.scrollY") as Number).toInt() > 0, "the preview alone shows the word")

        robot.interact {
            // Unsaved: the page shows the saved text, which the offsets of the edited one do not fit.
            editors.single().applyEdits(listOf(OffsetEdit(0, 0, "x")))
            editors.single().overlay("read-aloud", listOf(StyledRange(end - 3, end + 4, "speech-word")))
        }
        assertEquals(null, view.marked("speech-word"))
    }
}
