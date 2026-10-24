package dev.sluide.previews.preview

import dev.sluide.previews.markdown.MarkdownFileViewer
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.StackPane
import javafx.scene.web.WebEngine
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Collapse All and Expand All buttons of previews with foldable sections. Needs a display, see `uiTest`. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FoldControlsUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private class FoldingPreview(
        private val folding: Boolean = true,
    ) : Preview {
        override val folds get() = folding

        override fun load(
            engine: WebEngine,
            file: Path,
            text: String,
            dark: Boolean,
            root: Path,
        ) = engine.loadContent(
            HtmlPage.render(dark, emptyList(), listOf(HtmlPage.resource("preview.js"))) {
                append("<details open><summary>a</summary><details open><summary>b</summary>x</details></details>")
                append("<details open><summary>c</summary>y</details>")
            },
        )
    }

    private fun show(
        robot: FxRobot,
        preview: Preview,
    ): PreviewFileView {
        val file = dir.resolve("a.txt").apply { writeText("a") }
        val view =
            WaitForAsyncUtils
                .asyncFx<PreviewFileView> { PreviewFileView(file, MarkdownFileViewer().highlighter, preview) }
                .get()
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return view
    }

    private fun PreviewFileView.open(): Any? =
        WaitForAsyncUtils
            .asyncFx<Any?> { webView.engine.executeScript("document.querySelectorAll('details[open]').length") }
            .get()

    @Test
    fun `the buttons show with the preview only`(robot: FxRobot) {
        val view = show(robot, FoldingPreview())
        val folds = assertNotNull(view.folds)

        fun visible() = WaitForAsyncUtils.asyncFx<Boolean> { folds.node.isVisible }.get()
        robot.interact { view.mode.set(PreviewMode.SOURCE) }
        assertFalse(visible())
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        assertTrue(visible())
        assertNotNull(robot.lookup(".preview-collapse-all").queryAs(Button::class.java))
        assertNotNull(robot.lookup(".preview-expand-all").queryAs(Button::class.java))
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        assertTrue(visible())
    }

    @Test
    fun `Collapse All closes every section and Expand All opens them`(robot: FxRobot) {
        val view = show(robot, FoldingPreview())
        assertEquals(3, view.open())

        robot.interact { robot.lookup(".preview-collapse-all").queryAs(Button::class.java).fire() }
        assertEquals(0, view.open())
        robot.interact { robot.lookup(".preview-expand-all").queryAs(Button::class.java).fire() }
        assertEquals(3, view.open())
    }

    @Test
    fun `a preview that does not fold has no buttons`(robot: FxRobot) {
        val view = show(robot, FoldingPreview(folding = false))

        assertNull(view.folds)
        assertTrue(robot.lookup(".preview-collapse-all").queryAll<Button>().isEmpty())
    }

    @Test
    fun `the buttons do nothing before the page has loaded`(robot: FxRobot) {
        val view = show(robot, FoldingPreview())
        val controls = WaitForAsyncUtils.asyncFx<FoldControls> { FoldControls(view.webView.engine) { false } }.get()

        robot.interact {
            controls.collapseAllButton.fire()
            controls.setAllOpen(false)
        }
        assertEquals(3, view.open())
    }
}
