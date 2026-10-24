package dev.sluide.previews.json

import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewMode
import dev.sluide.previews.preview.sourceArea
import javafx.concurrent.Worker
import javafx.event.ActionEvent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
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
 * The JSON preview in a [PreviewFileView]: the modes, the fold buttons and the folds kept. Needs a display, see
 * `uiTest`. It stands for all fold-tree previews (JSON, XML, YAML), which share the fold state, the fold buttons and
 * Copy Formatted; the XML and YAML classes only test what is specific to their format.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JsonPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private val text = """{"deps": {"a": 1, "b": [1, 2]}, "other": {"c": true}}"""

    private fun show(
        robot: FxRobot,
        name: String = "data.json",
        content: String = text,
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve(name).apply { writeText(content) }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { JsonFileViewer().open(file) as PreviewFileView }.get()
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
        assertEquals(4, view.open(), "root, deps, b and other are containers")
        robot.interact { view.focus() }
        assertSame(view.sourceArea, WaitForAsyncUtils.asyncFx<Any?> { container.scene.focusOwner }.get())
    }

    private val nested =
        """{"a": {"x": {"p": [1, 2, 3]}, "y": [{"q": 1}, {"r": 2}]}, "b": {"z": {"s": [1]}}, "c": [[1, 2], [3]]}"""

    /** The closed containers on screen that are taller than their summary line, with both heights; empty if none. */
    private fun PreviewFileView.gaps() =
        script(
            """
            (function () {
                var gaps = [];
                var closed = document.querySelectorAll('details[data-path]:not([open])');
                for (var i = 0; i < closed.length; i++) {
                    if (closed[i].getClientRects().length === 0) continue;
                    var height = closed[i].getBoundingClientRect().height;
                    var line = closed[i].firstElementChild.getBoundingClientRect().height;
                    var path = closed[i].getAttribute('data-path');
                    if (Math.abs(height - line) > 1) gaps.push(path + ': ' + height + ' > ' + line);
                }
                return gaps.join('; ');
            })()
            """.trimIndent(),
        )

    private fun PreviewFileView.clickSummary(path: String) =
        script("document.querySelector('details[data-path=\"$path\"] > summary').click()")

    @Test
    fun `containers opened by hand after Collapse All show their closed children one line each`(robot: FxRobot) {
        val (_, view) = show(robot, content = nested)

        robot.fire(".preview-collapse-all")
        view.clickSummary("")
        assertEquals(true, view.isOpen(""))
        assertEquals("", view.gaps())
        view.clickSummary("/a")
        assertEquals(true, view.isOpen("/a"))
        assertEquals("", view.gaps())
    }

    @Test
    fun `Collapse All after a container was closed by hand leaves its children one line each`(robot: FxRobot) {
        val (_, view) = show(robot, content = nested)
        view.clickSummary("/a")

        robot.fire(".preview-collapse-all")
        view.clickSummary("")
        view.clickSummary("/a")
        assertEquals(true, view.isOpen("/a"))
        assertEquals("", view.gaps())
    }

    @Test
    fun `a closed container stays closed when the source is saved`(robot: FxRobot) {
        val (file, view) = show(robot)
        view.script("document.querySelector('details[data-path=\"/deps\"]').open = false")
        assertEquals(false, view.isOpen("/deps"))

        robot.interact { view.sourceArea.replaceText(text.length - 1, text.length - 1, ", \"more\": {\"d\": 1}") }
        robot.interact { view.save() }
        awaitLoaded(view)

        assertTrue(file.toFile().readText().contains("more"))
        assertEquals(1, view.script("document.querySelectorAll('details[data-path=\"/more\"]').length"))
        assertEquals(false, view.isOpen("/deps"))
        assertEquals(true, view.isOpen("/other"))
        assertEquals(true, view.isOpen("/more"))
        assertEquals(true, view.isOpen(""))
    }

    @Test
    fun `a closed container stays closed when the file is reloaded from disk`(robot: FxRobot) {
        val (file, view) = show(robot)
        view.script("document.querySelector('details[data-path=\"/deps\"]').open = false")

        file.writeText("""{"deps": {"a": 2}, "other": {"c": false}}""")
        robot.interact { view.reload() }
        awaitLoaded(view)

        assertEquals(false, view.isOpen("/deps"))
        assertEquals(true, view.isOpen("/other"))
        assertEquals(true, view.script("document.body.textContent.indexOf('false') >= 0"), "the new text shows")
    }

    @Test
    fun `folds survive a save that is invalid, empty or cut off in between`(robot: FxRobot) {
        val (file, view) = show(robot)
        view.script("document.querySelector('details[data-path=\"/other\"]').open = false")

        for (broken in listOf("{\"deps\": [1,\n", "", "{\"deps\" 1}")) {
            robot.interact { view.sourceArea.replaceText(0, view.sourceArea.length, broken) }
            robot.interact { view.save() }
            awaitLoaded(view)
        }
        robot.interact { view.sourceArea.replaceText(0, view.sourceArea.length, text) }
        robot.interact { view.save() }
        awaitLoaded(view)

        assertEquals(text, file.toFile().readText())
        assertEquals(false, view.isOpen("/other"))
        assertEquals(true, view.isOpen("/deps"))
    }

    @Test
    fun `a container missing from a valid save is forgotten and opens when it comes back`(robot: FxRobot) {
        val (_, view) = show(robot)
        view.script("document.querySelector('details[data-path=\"/other\"]').open = false")

        for (next in listOf("""{"deps": {"a": 1}}""", text)) {
            robot.interact { view.sourceArea.replaceText(0, view.sourceArea.length, next) }
            robot.interact { view.save() }
            awaitLoaded(view)
        }

        assertEquals(true, view.isOpen("/other"))
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
    fun `Copy Formatted shows in Split and Preview but not in Source`(robot: FxRobot) {
        val (_, view) = show(robot)
        val copy = checkNotNull(view.copyFormatted)

        for ((mode, visible) in listOf(
            PreviewMode.SOURCE to false,
            PreviewMode.SPLIT to true,
            PreviewMode.PREVIEW to true,
        )) {
            robot.interact { view.mode.set(mode) }
            assertEquals(visible, WaitForAsyncUtils.asyncFx<Boolean> { copy.node.isVisible }.get(), mode.name)
            assertEquals(visible, WaitForAsyncUtils.asyncFx<Boolean> { copy.node.isManaged }.get(), mode.name)
        }
    }

    @Test
    fun `Copy Formatted puts the saved text formatted on the clipboard, without unsaved edits`(robot: FxRobot) {
        val (_, view) = show(robot)
        robot.interact { view.sourceArea.replaceText(1, 1, "\"unsaved\": 1, ") }
        assertTrue(view.dirty.get())

        setClipboard("MARKER")
        robot.fire(".preview-copy-formatted")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            "{\n  \"deps\": {\n    \"a\": 1,\n    \"b\": [\n      1,\n      2\n    ]\n  },\n" +
                "  \"other\": {\n    \"c\": true\n  }\n}\n",
            clipboardText(),
        )
        assertEquals("Copied", view.message())
    }

    @Test
    fun `Copy Formatted leaves the clipboard alone when the saved text is invalid`(robot: FxRobot) {
        val (_, view) = show(robot)
        robot.interact { view.sourceArea.replaceText(0, view.sourceArea.length, "{\"a\" 1}") }
        robot.interact { view.save() }
        awaitLoaded(view)
        setClipboard("MARKER")

        robot.fire(".preview-copy-formatted")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("MARKER", clipboardText())
        assertTrue(view.message().startsWith("Not copied: Line "), view.message())
    }

    @Test
    fun `the Copy Formatted message goes away when its pause ends and when the file is rendered again`(robot: FxRobot) {
        val (_, view) = show(robot)
        val copy = checkNotNull(view.copyFormatted)

        robot.fire(".preview-copy-formatted")
        assertEquals("Copied", view.message())
        robot.interact {
            // Ends the pause at once, as its end would.
            copy.pause.stop()
            copy.pause.onFinished.handle(ActionEvent())
        }
        assertEquals("", view.message())

        robot.fire(".preview-copy-formatted")
        assertEquals("Copied", view.message())
        robot.interact { view.sourceArea.replaceText(0, 0, " ") }
        robot.interact { view.save() }
        awaitLoaded(view)
        assertEquals("", view.message())
    }

    /** The message of the banner of a generated file (e.g. a lock file) above the source, if it is shown. */
    private fun generatedMessage(robot: FxRobot): String? =
        robot
            .lookup(".generated-banner .label")
            .queryAllAs(Label::class.java)
            .singleOrNull()
            ?.text

    @Test
    fun `Pipfile lock opens as a JSON preview whose source has the banner`(robot: FxRobot) {
        val (_, view) = show(robot, "Pipfile.lock", "{\"_meta\": {}}\n")

        assertEquals(
            "Pipfile.lock is generated by Pipenv from Pipfile; change that file and run \"pipenv lock\" instead of " +
                "editing this one.",
            generatedMessage(robot),
        )
        assertTrue("property" in view.sourceArea.getStyleOfChar(2))
    }
}
