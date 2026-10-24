package dev.sluide.previews.xml

import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewMode
import dev.sluide.previews.preview.sourceArea
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The XML preview in a [PreviewFileView]: the modes, the fold buttons and the folds kept. Needs a display, see
 * `uiTest`.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class XmlPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private val text =
        """
        <project>
          <deps><a>1</a><b><x>1</x></b></deps>
          <other><c>true</c></other>
        </project>
        """.trimIndent()

    private val deps = "/project[1]/deps[1]"
    private val other = "/project[1]/other[1]"

    private fun show(
        robot: FxRobot,
        name: String = "data.xml",
        content: String = text,
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve(name).apply { writeText(content) }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { XmlFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        // The page loads even while the preview is hidden.
        awaitLoaded(view)
        return file to view
    }

    private fun awaitLoaded(view: PreviewFileView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun PreviewFileView.script(code: String): Any? =
        WaitForAsyncUtils.asyncFx<Any?> { webView.engine.executeScript(code) }.get()

    private fun PreviewFileView.open() = script("document.querySelectorAll('details[open]').length")

    private fun PreviewFileView.isOpen(path: String) =
        script("document.querySelector('details[data-path=\"$path\"]').open")

    private fun FxRobot.fire(styleClass: String) = interact { lookup(styleClass).queryAs(Button::class.java).fire() }

    @Test
    fun `opens in split with the page and the fold buttons, the source focused`(robot: FxRobot) {
        val (_, view) = show(robot)
        val folds = checkNotNull(view.folds)

        assertEquals(PreviewMode.SPLIT, view.mode.get())
        assertTrue(WaitForAsyncUtils.asyncFx<Boolean> { folds.node.isVisible }.get())
        assertEquals(4, view.open(), "project, deps, b and other are elements with children")
        robot.interact { view.focus() }
        assertSame(view.sourceArea, WaitForAsyncUtils.asyncFx<Any?> { container.scene.focusOwner }.get())
    }

    @Test
    fun `a closed element stays closed when the source is saved`(robot: FxRobot) {
        val (file, view) = show(robot)
        view.script("document.querySelector('details[data-path=\"$deps\"]').open = false")
        assertEquals(false, view.isOpen(deps))

        val end = text.indexOf("</project>")
        robot.interact { view.sourceArea.replaceText(end, end, "<more><d>1</d></more>\n") }
        robot.interact { view.save() }
        awaitLoaded(view)

        assertTrue(file.toFile().readText().contains("more"))
        assertEquals(1, view.script("document.querySelectorAll('details[data-path=\"/project[1]/more[1]\"]').length"))
        assertEquals(false, view.isOpen(deps))
        assertEquals(true, view.isOpen(other))
        assertEquals(true, view.isOpen("/project[1]/more[1]"))
        assertEquals(true, view.isOpen("/project[1]"))
    }

    private fun clipboardText(): String? =
        WaitForAsyncUtils
            .asyncFx<String?> {
                Clipboard.getSystemClipboard().string
            }.get()

    private fun setClipboard(text: String) =
        WaitForAsyncUtils
            .asyncFx {
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
            }.get()

    private fun PreviewFileView.message() =
        WaitForAsyncUtils
            .asyncFx<String> {
                checkNotNull(copyFormatted).message.text
            }.get()

    @Test
    fun `Copy Formatted puts the saved text formatted on the clipboard, without unsaved edits`(robot: FxRobot) {
        val (_, view) = show(robot)
        robot.interact { view.sourceArea.replaceText(0, 0, "<!-- unsaved -->\n") }
        assertTrue(view.dirty.get())

        setClipboard("MARKER")
        robot.fire(".preview-copy-formatted")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            "<project>\n  <deps>\n    <a>1</a>\n    <b>\n      <x>1</x>\n    </b>\n  </deps>\n" +
                "  <other>\n    <c>true</c>\n  </other>\n</project>\n",
            clipboardText(),
        )
        assertEquals("Copied", view.message())
    }
}
