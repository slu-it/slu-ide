package dev.sluide.fileviewers.xml

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
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve(name).apply { writeText(text) }
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
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(view.source.area, WaitForAsyncUtils.asyncFx<Any?> { container.scene.focusOwner }.get())
    }

    @Test
    fun `Source hides the fold buttons`(robot: FxRobot) {
        val (_, view) = show(robot)
        val folds = checkNotNull(view.folds)

        robot.interact { view.mode.set(PreviewMode.SOURCE) }
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { folds.node.isVisible }.get())
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
    fun `a closed element stays closed when the source is saved`(robot: FxRobot) {
        val (file, view) = show(robot)
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.script("document.querySelector('details[data-path=\"$deps\"]').open = false")
        assertEquals(false, view.isOpen(deps))

        val end = text.indexOf("</project>")
        robot.interact { view.source.area.replaceText(end, end, "<more><d>1</d></more>\n") }
        robot.interact { view.save() }
        awaitLoaded(view)

        assertTrue(file.toFile().readText().contains("more"))
        assertEquals(1, view.script("document.querySelectorAll('details[data-path=\"/project[1]/more[1]\"]').length"))
        assertEquals(false, view.isOpen(deps))
        assertEquals(true, view.isOpen(other))
        assertEquals(true, view.isOpen("/project[1]/more[1]"))
        assertEquals(true, view.isOpen("/project[1]"))
    }

    @Test
    fun `a closed element stays closed when the file is reloaded from disk`(robot: FxRobot) {
        val (file, view) = show(robot)
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.script("document.querySelector('details[data-path=\"$deps\"]').open = false")

        file.writeText("<project><deps><a>2</a></deps><other><c>fresh</c></other></project>")
        robot.interact { view.reload() }
        awaitLoaded(view)

        assertEquals(false, view.isOpen(deps))
        assertEquals(true, view.isOpen(other))
        assertEquals(true, view.script("document.body.textContent.indexOf('fresh') >= 0"), "the new text shows")
    }

    @Test
    fun `folds survive a save that is invalid, empty or cut off in between`(robot: FxRobot) {
        val (file, view) = show(robot)
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.script("document.querySelector('details[data-path=\"$other\"]').open = false")

        for (broken in listOf("<project>\n<deps>\n", "", "<project>\n<deps>&nope;</deps>")) {
            robot.interact { view.source.area.replaceText(0, view.source.area.length, broken) }
            robot.interact { view.save() }
            awaitLoaded(view)
        }
        robot.interact { view.source.area.replaceText(0, view.source.area.length, text) }
        robot.interact { view.save() }
        awaitLoaded(view)

        assertEquals(text, file.toFile().readText())
        assertEquals(false, view.isOpen(other))
        assertEquals(true, view.isOpen(deps))
    }

    @Test
    fun `an element missing from a valid save is forgotten and opens when it comes back`(robot: FxRobot) {
        val (_, view) = show(robot)
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.script("document.querySelector('details[data-path=\"$other\"]').open = false")

        for (next in listOf("<project><deps><a>1</a><b><x>1</x></b></deps></project>", text)) {
            robot.interact { view.source.area.replaceText(0, view.source.area.length, next) }
            robot.interact { view.save() }
            awaitLoaded(view)
        }

        assertEquals(true, view.isOpen(other))
    }

    @Test
    fun `an invalid file shows the error and the page so far`(robot: FxRobot) {
        val (_, view) = show(robot, "broken.xml")
        robot.interact {
            view.source.area.replaceText(
                0,
                view.source.area.length,
                "<a>\n<b>1</b>\n<c>&nope;</c>\n</a>",
            )
        }
        robot.interact { view.save() }
        awaitLoaded(view)

        assertEquals(true, view.script("document.querySelector('.fold-error').textContent.indexOf('Line 3') == 0"))
        assertEquals(1, view.script("document.querySelectorAll('details').length"))
    }
}
