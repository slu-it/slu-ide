package dev.sluide.previews.markdown

import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewMode
import dev.sluide.previews.preview.ZoomSteps
import dev.sluide.previews.preview.sourceArea
import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Mermaid files with a real WebView and the bundled Mermaid. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MermaidPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun show(
        robot: FxRobot,
        source: String,
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve("flow.mmd").apply { writeText(source) }
        val view =
            WaitForAsyncUtils
                .asyncFx<PreviewFileView> {
                    MermaidFileViewer().open(
                        file,
                    ) as PreviewFileView
                }.get()
        robot.interact { container.children.setAll(view.node) }
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

    private fun PreviewFileView.awaitTrue(condition: String) =
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { script(condition) == true }

    /** Waits until the page has drawn its diagram: drawings run one after the other, so one more ends after them. */
    private fun PreviewFileView.awaitDrawing() {
        script("window.drawingDone = false; sluide.renderDiagrams().then(function () { window.drawingDone = true; })")
        awaitTrue("window.drawingDone === true")
    }

    private fun PreviewFileView.number(code: String) = (script(code) as Number).toDouble()

    private val svgs = "document.querySelectorAll('pre.mermaid svg').length"
    private val failed = "document.querySelectorAll('pre.mermaid.error').length === 1"
    private val svgWidth = "document.querySelector('pre.mermaid svg').getBoundingClientRect().width"
    private val actualWidth = "parseFloat(document.querySelector('pre.mermaid svg').getAttribute('data-width'))"
    private val clickDiagram =
        "document.querySelector('pre.mermaid svg').dispatchEvent(new MouseEvent('click', { bubbles: true }))"

    @Test
    fun `a Mermaid file opens side by side and draws its diagram, again in the other colour scheme`(robot: FxRobot) {
        val (_, view) = show(robot, "flowchart LR\n  Start --> Stop\n")

        assertEquals(PreviewMode.SPLIT, view.mode.get())
        view.awaitDrawing()
        assertEquals(1.0, view.number(svgs))
        assertTrue((view.script("document.querySelector('pre.mermaid svg').textContent") as String).contains("Start"))
        assertEquals(
            "rgba(0, 0, 0, 0)",
            view.script("getComputedStyle(document.querySelector('pre.mermaid')).backgroundColor"),
        )

        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")
        view.awaitDrawing()
        assertEquals(1.0, view.number(svgs))
        assertTrue((view.script("document.querySelector('pre.mermaid svg').textContent") as String).contains("Stop"))
        assertEquals(
            "rgba(0, 0, 0, 0)",
            view.script("getComputedStyle(document.querySelector('pre.mermaid')).backgroundColor"),
            "no code box in the dark theme either",
        )
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    @Test
    fun `a diagram that fits shows at its actual size, a wider one fits the page until clicked`(robot: FxRobot) {
        val (file, view) = show(robot, "flowchart TD\n  A --> B\n")
        view.awaitDrawing()
        assertEquals(view.number(actualWidth), view.number(svgWidth), 1.0)
        val zoom = view.zoom
        assertTrue(fx { zoom.fit })
        assertEquals("100%", fx { zoom.level.text })

        file.writeText("flowchart LR\n" + (1..12).joinToString("\n") { "  Step$it --> Step${it + 1}" } + "\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        view.awaitDrawing()
        val pageWidth = view.number("document.body.clientWidth")
        assertTrue(view.number(actualWidth) > pageWidth, "the diagram is wider than the page")
        assertTrue(view.number(svgWidth) <= pageWidth)
        assertEquals(ZoomSteps.label(view.number(svgWidth) / view.number(actualWidth)), fx { zoom.level.text })
        assertTrue(view.number("document.documentElement.scrollWidth") <= view.number("window.innerWidth"))
        assertTrue(
            view.number("document.documentElement.scrollHeight") <= view.number("window.innerHeight"),
            "no scrollbar",
        )

        view.script(clickDiagram)
        assertEquals(view.number(actualWidth), view.number(svgWidth), 1.0)
        assertFalse(fx { zoom.fit })
        assertEquals("100%", fx { zoom.level.text })
        assertTrue(view.number("document.documentElement.scrollWidth") > pageWidth, "the page scrolls")
        view.script("window.scrollTo(0, 0)")
        assertTrue(view.number("document.querySelector('pre.mermaid svg').getBoundingClientRect().left") >= 0.0)

        // A theme change draws it again, still at its actual size.
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")
        view.awaitDrawing()
        assertEquals(view.number(actualWidth), view.number(svgWidth), 1.0)

        // So does a reload.
        file.writeText("flowchart LR\n" + (1..14).joinToString("\n") { "  Step$it --> Step${it + 1}" } + "\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        view.awaitDrawing()
        assertEquals(view.number(actualWidth), view.number(svgWidth), 1.0)
        assertFalse(fx { zoom.fit })

        view.script(clickDiagram)
        assertTrue(view.number(svgWidth) <= pageWidth)
        assertTrue(fx { zoom.fit && zoom.fitButton?.isSelected == true })
    }

    @Test
    fun `a huge diagram zoomed out below 10 percent keeps its scale when it is drawn again`(robot: FxRobot) {
        fun chain(steps: Int) = "flowchart LR\n" + (1..steps).joinToString("\n") { "  S$it --> S${it + 1}" } + "\n"
        val (file, view) = show(robot, chain(120))
        view.awaitDrawing()
        val zoom = view.zoom
        val fitted = fx { zoom.scale }
        assertTrue(fitted < 0.1 && fx { zoom.fit }, "fitted at $fitted")

        robot.interact { zoom.zoomOutButton.fire() }
        assertFalse(fx { zoom.fit })
        assertEquals(fitted, fx { zoom.scale }, 1e-9)

        // Drawn again, also when the new diagram on its own would fit at a larger scale.
        file.writeText(chain(40))
        robot.interact { view.reload() }
        awaitLoaded(view)
        view.awaitDrawing()
        assertEquals(fitted, fx { zoom.scale }, 1e-9)
        assertFalse(fx { zoom.fit })
        assertEquals(fitted, view.number(svgWidth) / view.number(actualWidth), 1e-3)
    }

    @Test
    fun `the zoom controls size the diagram`(robot: FxRobot) {
        val (_, view) = show(robot, "flowchart TD\n  A --> B\n")
        view.awaitDrawing()
        val zoom = view.zoom
        val actual = view.number(actualWidth)

        robot.interact { zoom.zoomInButton.fire() }
        assertEquals(actual * 1.25, view.number(svgWidth), 1.0)
        assertEquals("125%", fx { zoom.level.text })
        robot.interact { zoom.zoomOutButton.fire() }
        robot.interact { zoom.zoomOutButton.fire() }
        assertEquals(actual * 0.75, view.number(svgWidth), 1.0)
        robot.interact { zoom.actualSizeButton.fire() }
        assertEquals(actual, view.number(svgWidth), 1.0)
        assertFalse(fx { zoom.fit })
        robot.interact { checkNotNull(zoom.fitButton).fire() }
        assertTrue(fx { zoom.fit })
    }

    @Test
    fun `a diagram that does not parse shows Mermaid's error, and the diagram once it is fixed`(robot: FxRobot) {
        val (file, view) = show(robot, "flowchart LR\n  Start -->\n")

        view.awaitDrawing()
        assertEquals(true, view.script(failed))
        val message = view.script("document.querySelector('pre.mermaid').textContent") as String
        assertTrue("Parse error on line" in message, message)
        assertEquals(0.0, view.number(svgs))

        // A theme change draws it again, with the error again.
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")
        view.awaitDrawing()
        assertEquals(true, view.script(failed))

        file.writeText("flowchart LR\n  Start --> Stop\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        view.awaitDrawing()
        assertEquals(1.0, view.number(svgs))
        assertEquals(false, view.script("document.querySelector('pre.mermaid').classList.contains('error')"))
    }

    @Test
    fun `an unknown diagram type is an error too, an empty file is none`(robot: FxRobot) {
        val (file, view) = show(robot, "flowchat LR\n  A --> B\n")

        view.awaitDrawing()
        assertEquals(true, view.script(failed))
        assertTrue((view.script("document.querySelector('pre.mermaid').textContent") as String).isNotBlank())

        file.writeText("")
        robot.interact { view.reload() }
        awaitLoaded(view)
        view.awaitDrawing()
        assertEquals(false, view.script("document.querySelector('pre.mermaid').classList.contains('error')"))
        assertEquals("", view.script("document.querySelector('pre.mermaid').textContent"))
        assertEquals(0.0, view.number(svgs))
    }

    @Test
    fun `a Mermaid file in Split gives the focus to its diagram`(robot: FxRobot) {
        val file = dir.resolve("flow.mmd").apply { writeText("flowchart LR\n  A --> B\n") }
        val view = fx { MermaidFileViewer().open(file) as PreviewFileView }
        // Asked before it is shown: the split pane adds the web view to the scene only when it is skinned.
        robot.interact {
            view.focus()
            container.children.setAll(view.node)
        }
        awaitLoaded(view)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(PreviewMode.SPLIT, fx { view.mode.get() })
        assertSame(view.webView, fx { container.scene.focusOwner })
    }

    @Test
    fun `a Mermaid file left in Source and shown again does not take the focus when switched to Split`(robot: FxRobot) {
        val (_, view) = show(robot, "flowchart LR\n  A --> B\n")
        robot.interact { view.mode.set(PreviewMode.SOURCE) }
        robot.interact { container.children.clear() }
        robot.interact {
            container.children.setAll(view.node)
            view.focus()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(view.sourceArea, fx { container.scene.focusOwner }, "Source: the source")

        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        WaitForAsyncUtils.waitForFxEvents()

        assertSame(view.sourceArea, fx { container.scene.focusOwner })
    }
}
