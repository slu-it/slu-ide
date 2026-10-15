package dev.sluide.fileviewers

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
import kotlin.test.assertTrue

/** Bicep files with a real WebView and the bundled Mermaid. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BicepPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 1000.0, 500.0)
        stage.show()
    }

    private fun show(
        robot: FxRobot,
        source: String,
    ): PreviewFileView {
        val file = dir.resolve("main.bicep").apply { writeText(source) }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { BicepFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return view
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

    private fun PreviewFileView.count(selector: String) =
        (script("document.querySelectorAll('$selector').length") as Number).toInt()

    /** The texts of the diagram's nodes and edge labels, one per line. */
    private fun PreviewFileView.diagramText() =
        script(
            "Array.prototype.map.call(" +
                "document.querySelectorAll('pre.mermaid svg g.node, pre.mermaid svg g.edgeLabel'), " +
                "function (e) { return e.textContent; }).join('\\n')",
        ) as String

    @Test
    fun `a Bicep file opens as source, its preview draws the resource graph in both colour schemes`(robot: FxRobot) {
        val view = show(robot, MAIN_BICEP)
        assertEquals(PreviewMode.SOURCE, view.mode.get())

        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        view.awaitDrawing()
        assertEquals(0, view.count("pre.mermaid.error"))
        assertEquals(5, view.count("pre.mermaid svg g.node"))
        assertEquals(1, view.count("pre.mermaid svg g.node.existing"))
        val text = view.diagramText()
        for (part in listOf("storage", "storageAccounts", "container (loop)", "vault", "./modules/network.bicep")) {
            assertTrue(part in text, "$part in $text")
        }
        assertEquals(2, Regex("parent").findAll(text).count(), text)
        assertFalse("<br" in text, "line breaks are no text")

        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")
        view.awaitDrawing()
        assertEquals(5, view.count("pre.mermaid svg g.node"))
        assertEquals(0, view.count("pre.mermaid.error"))
    }

    @Test
    fun `hostile names are drawn as text`(robot: FxRobot) {
        val path = "x\"]; %% #quot; <b>bold</b> `md` 😀.bicep"
        val view = show(robot, "module m '$path' = {}\nresource r 'A' existing = { name: m.name }\n")
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        view.awaitDrawing()

        assertEquals(0, view.count("pre.mermaid.error"))
        assertEquals(2, view.count("pre.mermaid svg g.node"))
        assertEquals(0, view.count("pre.mermaid svg b"))
        val text = view.diagramText()
        assertTrue(path in text, text)
    }

    @Test
    fun `a file without resources shows a note instead`(robot: FxRobot) {
        val view = show(robot, "param location string\noutput where string = location\n")
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }

        assertEquals(0, view.count("pre.mermaid"))
        assertEquals(
            "No resources or modules in this file.",
            view.script("document.querySelector('p.diagram-note').textContent"),
        )
    }
}
