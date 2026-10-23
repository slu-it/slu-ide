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
import kotlin.test.assertFalse
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
    private val ports = "/0/spec/ports"
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
    fun `a yml file opens too and empty collections get no details`(robot: FxRobot) {
        val (_, view) = show(robot, "data.yml", "a: {}\nb: []\nc:\n  d: 1\n")

        assertEquals(1, view.open())
        assertEquals(true, view.script("document.body.textContent.indexOf('a: {}') >= 0"))
        assertEquals(true, view.script("document.body.textContent.indexOf('b: []') >= 0"))
    }

    @Test
    fun `the page shows flow collections expanded and indented`(robot: FxRobot) {
        val (_, view) = show(robot)

        assertEquals(
            2,
            view.script("document.querySelectorAll('details[data-path=\"$ports\"] .children > div').length"),
        )
        assertEquals(
            true,
            view.script(
                "document.querySelector('details[data-path=\"$spec\"] > summary').textContent.indexOf('spec:') == 0",
            ),
        )
    }

    @Test
    fun `a closed node shows how many keys or items it holds`(robot: FxRobot) {
        val (_, view) = show(robot)

        robot.fire(".preview-collapse-all")

        fun tail(path: String) =
            view.script("document.querySelector('details[data-path=\"$path\"] > summary .tail').textContent")
        assertEquals(" … 2 keys", tail(spec))
        assertEquals(" … 2 items", tail(ports))
        assertEquals(" … 1 item", tail(other))
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
    fun `Source hides the fold buttons`(robot: FxRobot) {
        val (_, view) = show(robot)
        val folds = checkNotNull(view.folds)

        robot.interact { view.mode.set(PreviewMode.SOURCE) }
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { folds.node.isVisible }.get())
    }

    @Test
    fun `Collapse All and Expand All fold every node`(robot: FxRobot) {
        val (_, view) = show(robot)

        robot.fire(".preview-collapse-all")
        assertEquals(0, view.open())
        robot.fire(".preview-expand-all")
        assertEquals(4, view.open())
    }

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

    @Test
    fun `a closed node stays closed when the file is reloaded from disk`(robot: FxRobot) {
        val (file, view) = show(robot)
        view.close(spec)

        file.writeText("spec:\n  a: 1\n  b: fresh\nother:\n  - y\n")
        robot.interact { view.reload() }
        awaitLoaded(view)

        assertEquals(false, view.isOpen(spec))
        assertEquals(true, view.isOpen(other))
        assertEquals(true, view.script("document.body.textContent.indexOf('fresh') >= 0"), "the new text shows")
    }

    /** A cut-off page keeps the folds the same way; YamlRendererTest covers it (the node cap is too large here). */
    @Test
    fun `folds survive a save that is invalid or empty in between`(robot: FxRobot) {
        val (file, view) = show(robot)
        view.close(other)

        for (broken in listOf("spec:\n  a: [1,\n", "", "a: 1\n\tb: 2\n")) {
            view.save(broken, robot)
            assertTrue(
                view.script("document.getElementById('fold-state').dataset.collapsed").toString().contains(other),
                "fold-state keeps the folds after: $broken",
            )
        }
        view.save(text, robot)

        assertEquals(text, file.toFile().readText())
        assertEquals(false, view.isOpen(other))
        assertEquals(true, view.isOpen(spec))
    }

    @Test
    fun `a node missing from a valid save is forgotten and opens when it comes back`(robot: FxRobot) {
        val (_, view) = show(robot)
        view.close(other)

        view.save("name: web\nspec:\n  ports: [80]\n", robot)
        view.save(text, robot)

        assertEquals(true, view.isOpen(other))
    }

    @Test
    fun `a multi-document file shows a separator line between the documents`(robot: FxRobot) {
        val (_, view) = show(robot, content = "a:\n  x: 1\n---\nb:\n  y: 2\n---\n- z\n")

        assertEquals(
            2,
            view.script(
                "Array.prototype.filter.call(document.querySelectorAll('.fold-tree div'), function (d) { return d.textContent === '---' }).length",
            ),
        )
        assertEquals(1, view.script("document.querySelectorAll('details[data-path=\"/0/a\"]').length"))
        assertEquals(1, view.script("document.querySelectorAll('details[data-path=\"/1/b\"]').length"))
        // Documents do not fold themselves.
        assertEquals(0, view.script("document.querySelectorAll('details[data-path=\"/2\"]').length"))
    }

    @Test
    fun `an invalid file shows the error and the tree so far`(robot: FxRobot) {
        val (_, view) = show(robot, "broken.yaml")

        view.save("k:\n  x: 1\n  y: 2\nm:\n\tz: 1\n", robot)

        assertEquals(true, view.script("document.querySelector('.fold-error').textContent.indexOf('Line ') == 0"))
        assertEquals(1, view.script("document.querySelectorAll('details[data-path=\"/0/k\"]').length"))
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
    fun `Copy Formatted shows in Split and Preview but not in Source`(robot: FxRobot) {
        val (_, view) = show(robot)
        val copy = checkNotNull(view.copyFormatted)

        for ((mode, visible) in listOf(
            PreviewMode.SOURCE to false,
            PreviewMode.SPLIT to true,
            PreviewMode.PREVIEW to true,
        )) {
            robot.interact { view.mode.set(mode) }
            WaitForAsyncUtils.waitForFxEvents()
            assertEquals(visible, WaitForAsyncUtils.asyncFx<Boolean> { copy.node.isVisible }.get(), mode.name)
        }
    }

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

    @Test
    fun `Copy Formatted leaves the clipboard alone when the saved text is invalid`(robot: FxRobot) {
        val (_, view) = show(robot)
        view.save("a:\n  b: 1\n\tc: 2\n", robot)
        setClipboard("MARKER")

        robot.fire(".preview-copy-formatted")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("MARKER", clipboardText())
        assertTrue(view.message().startsWith("Not copied: Line "), view.message())
    }

    @Test
    fun `Copy Formatted says so when the saved file is empty`(robot: FxRobot) {
        val (_, view) = show(robot)
        view.save("", robot)
        setClipboard("MARKER")

        robot.fire(".preview-copy-formatted")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("MARKER", clipboardText())
        assertEquals("Not copied: The file is empty", view.message())
    }

    @Test
    fun `Copy Formatted separates documents with a line`(robot: FxRobot) {
        val (_, view) = show(robot, content = "a: 1\n---\nb: 2\n")

        robot.fire(".preview-copy-formatted")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("a: 1\n---\nb: 2\n", clipboardText())
    }
}
