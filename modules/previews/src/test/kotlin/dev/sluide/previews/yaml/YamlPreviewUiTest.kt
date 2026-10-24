package dev.sluide.previews.yaml

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
 * The YAML preview in a [PreviewFileView]: the modes, the fold buttons and the folds kept. Needs a display, see
 * `uiTest`.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class YamlPreviewUiTest {
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
        name: web
        spec:
          ports: [80, 443]
          env:
            A: 1
            B: 2
        other:
          - x
        """.trimIndent() + "\n"

    private val spec = "/0/spec"
    private val other = "/0/other"

    private fun show(
        robot: FxRobot,
        name: String = "data.yaml",
        content: String = text,
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve(name).apply { writeText(content) }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { YamlFileViewer().open(file) as PreviewFileView }.get()
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

    private fun PreviewFileView.save(
        content: String,
        robot: FxRobot,
    ) {
        robot.interact { sourceArea.replaceText(0, sourceArea.length, content) }
        robot.interact { save() }
        awaitLoaded(this)
    }

    private fun PreviewFileView.close(path: String) =
        script("document.querySelector('details[data-path=\"$path\"]').open = false")

    @Test
    fun `opens in split with the source focused and a details per non-empty collection`(robot: FxRobot) {
        val (_, view) = show(robot, "data.yaml")
        val folds = checkNotNull(view.folds)

        assertEquals(PreviewMode.SPLIT, view.mode.get())
        assertTrue(WaitForAsyncUtils.asyncFx<Boolean> { folds.node.isVisible }.get())
        assertEquals(4, view.open(), "spec, ports, env and other are non-empty collections")
        robot.interact { view.focus() }
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(view.sourceArea, WaitForAsyncUtils.asyncFx<Any?> { container.scene.focusOwner }.get())
    }

    @Test
    fun `clicking a node folds it and clicking again opens it`(robot: FxRobot) {
        val (_, view) = show(robot)

        view.clickSummary(spec)
        assertEquals(false, view.isOpen(spec))
        assertEquals(true, view.isOpen(other))
        view.clickSummary(spec)
        assertEquals(true, view.isOpen(spec))
    }

    private fun PreviewFileView.clickSummary(path: String) =
        script("document.querySelector('details[data-path=\"$path\"] > summary').click()")

    @Test
    fun `a closed node stays closed when the source is saved`(robot: FxRobot) {
        val (file, view) = show(robot)
        view.close(spec)

        view.save(text + "more:\n  - k: 1\n", robot)

        assertTrue(file.toFile().readText().contains("more"))
        assertEquals(false, view.isOpen(spec))
        assertEquals(true, view.isOpen(other))
        assertEquals(true, view.isOpen("/0/more"), "a new node opens")
    }

    private fun clipboardText(): String? =
        WaitForAsyncUtils.asyncFx<String?> { Clipboard.getSystemClipboard().string }.get()

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
        robot.interact { view.sourceArea.replaceText(0, 0, "# unsaved\n") }
        assertTrue(view.dirty.get())

        setClipboard("MARKER")
        robot.fire(".preview-copy-formatted")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            "name: web\nspec:\n  ports:\n    - 80\n    - 443\n  env:\n    A: 1\n    B: 2\nother:\n  - x\n",
            clipboardText(),
        )
        assertEquals("Copied", view.message())
    }
}
