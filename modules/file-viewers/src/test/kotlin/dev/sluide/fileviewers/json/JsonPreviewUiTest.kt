package dev.sluide.fileviewers.json

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.preview.PreviewFileView
import dev.sluide.fileviewers.preview.PreviewMode
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.control.Button
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
import kotlin.test.assertTrue

/** The JSON preview in a [PreviewFileView]: the modes, the fold buttons and the folds kept. Needs a display, see `uiTest`. */
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
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve(name).apply { writeText(text) }
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
    fun `opens on the source without fold buttons, split shows the page and the buttons`(robot: FxRobot) {
        val (_, view) = show(robot)
        val folds = checkNotNull(view.folds)

        assertEquals(PreviewMode.SOURCE, view.mode.get())
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { folds.node.isVisible }.get())
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(WaitForAsyncUtils.asyncFx<Boolean> { folds.node.isVisible }.get())
        assertEquals(4, view.open(), "root, deps, b and other are containers")
    }

    @Test
    fun `Collapse All and Expand All fold every container`(robot: FxRobot) {
        val (_, view) = show(robot)
        robot.interact { view.mode.set(PreviewMode.SPLIT) }

        robot.fire(".preview-collapse-all")
        assertEquals(0, view.open())
        robot.fire(".preview-expand-all")
        assertEquals(4, view.open())
    }

    @Test
    fun `a closed container stays closed when the source is saved`(robot: FxRobot) {
        val (file, view) = show(robot)
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.script("document.querySelector('details[data-path=\"/deps\"]').open = false")
        assertEquals(false, view.isOpen("/deps"))

        robot.interact { view.source.area.replaceText(text.length - 1, text.length - 1, ", \"more\": {\"d\": 1}") }
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
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
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
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.script("document.querySelector('details[data-path=\"/other\"]').open = false")

        for (broken in listOf("{\"deps\": [1,\n", "", "{\"deps\": [1,\n")) {
            robot.interact { view.source.area.replaceText(0, view.source.area.length, broken) }
            robot.interact { view.save() }
            awaitLoaded(view)
        }
        robot.interact { view.source.area.replaceText(0, view.source.area.length, text) }
        robot.interact { view.save() }
        awaitLoaded(view)

        assertEquals(text, file.toFile().readText())
        assertEquals(false, view.isOpen("/other"))
        assertEquals(true, view.isOpen("/deps"))
    }

    @Test
    fun `a container missing from a valid save is forgotten and opens when it comes back`(robot: FxRobot) {
        val (_, view) = show(robot)
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.script("document.querySelector('details[data-path=\"/other\"]').open = false")

        for (next in listOf("""{"deps": {"a": 1}}""", text)) {
            robot.interact { view.source.area.replaceText(0, view.source.area.length, next) }
            robot.interact { view.save() }
            awaitLoaded(view)
        }

        assertEquals(true, view.isOpen("/other"))
    }

    @Test
    fun `Pipfile lock opens as a JSON preview`(robot: FxRobot) {
        val (file, view) = show(robot, "Pipfile.lock")

        assertTrue(FileViewers(listOf(JsonFileViewer()), DefaultTextFileViewer()).viewerFor(file) is JsonFileViewer)
        assertEquals(4, view.open())
    }
}
