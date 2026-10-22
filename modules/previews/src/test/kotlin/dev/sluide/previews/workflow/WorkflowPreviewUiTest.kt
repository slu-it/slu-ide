package dev.sluide.previews.workflow

import dev.sluide.githubactions.WorkflowGraphs
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewMode
import dev.sluide.previews.preview.sourceArea
import dev.sluide.textediting.CommentSyntax
import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Workflows with a real WebView, the bundled parser and the bundled Mermaid: the graphs of the repository's workflows
 * and of the parser's fixtures draw without a Mermaid error. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class WorkflowPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    /** Parses with the real parser once [gate] opens. */
    private val gate = CountDownLatch(1)
    private val parsed = CopyOnWriteArrayList<String>()
    private val gatedParser =
        WorkflowParser { fileName, text ->
            gate.await(10, TimeUnit.SECONDS)
            parsed += text
            WebViewWorkflowParser.shared.parse(fileName, text)
        }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 1000.0, 500.0)
        stage.show()
    }

    @AfterEach
    fun openGate() = gate.countDown()

    private fun workflowsFolder() = dir.resolve(".github/workflows").createDirectories()

    /** Opens a copy of the repository's [name] at `.github/workflows/` in [dir] with the workflow viewer. */
    private fun show(
        robot: FxRobot,
        name: String,
    ): Pair<Path, PreviewFileView> {
        val file = WorkflowFiles.workflow(name).copyTo(workflowsFolder().resolve(name))
        return file to show(robot) { WorkflowFileViewer().open(file) as PreviewFileView }
    }

    /** Opens [file] with a preview that parses with [gatedParser]. */
    private fun showGated(
        robot: FxRobot,
        file: Path,
    ): PreviewFileView =
        show(robot) {
            val viewer = WorkflowFileViewer()
            PreviewFileView(file, viewer.highlighter, WorkflowPreview(gatedParser), CommentSyntax.HASH)
        }

    private fun show(
        robot: FxRobot,
        open: () -> PreviewFileView,
    ): PreviewFileView {
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { open() }.get()
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView.engine)
        return view
    }

    private fun awaitLoaded(engine: WebEngine) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun WebEngine.script(code: String): Any? = WaitForAsyncUtils.asyncFx<Any?> { executeScript(code) }.get()

    private fun PreviewFileView.script(code: String): Any? = webView.engine.script(code)

    private fun WebEngine.awaitTrue(condition: String) =
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { script(condition) == true }

    /** Waits until the page has drawn its diagram: drawings run one after the other, so one more ends after them. */
    private fun WebEngine.awaitDrawing() {
        script("window.drawingDone = false; sluide.renderDiagrams().then(function () { window.drawingDone = true; })")
        awaitTrue("window.drawingDone === true")
    }

    /** Waits until the page shows a graph (not the note while parsing) and Mermaid has drawn it. */
    private fun PreviewFileView.awaitGraph() {
        webView.engine.awaitTrue("document.querySelector('pre.mermaid') !== null")
        webView.engine.awaitDrawing()
    }

    private fun WebEngine.count(selector: String) =
        (script("document.querySelectorAll('$selector').length") as Number).toInt()

    private fun PreviewFileView.count(selector: String) = webView.engine.count(selector)

    private fun WebEngine.mermaidError() =
        script("var e = document.querySelector('pre.mermaid.error'); e && e.textContent")

    private fun PreviewFileView.note() = script("var p = document.querySelector('p.diagram-note'); p && p.textContent")

    /** The texts of the diagram's nodes and subgraph titles, one per line. */
    private fun PreviewFileView.diagramText() =
        script(
            "Array.prototype.map.call(" +
                "document.querySelectorAll('pre.mermaid svg g.node, pre.mermaid svg g.cluster-label'), " +
                "function (e) { return e.textContent; }).join('\\n')",
        ) as String

    @Test
    fun `a workflow opens as source, says it reads the workflow, then draws it in both colour schemes`(
        robot: FxRobot,
    ) {
        val file = WorkflowFiles.workflow("ci.yml").copyTo(workflowsFolder().resolve("ci.yml"))
        val view = showGated(robot, file)
        assertEquals(PreviewMode.SOURCE, view.mode.get())
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        assertEquals(WorkflowPreview.READING, view.note())
        assertEquals(0, view.count("pre.mermaid"))

        gate.countDown()
        view.awaitGraph()
        assertEquals(null, view.webView.engine.mermaidError())
        assertEquals(1, view.count("pre.mermaid svg"))
        assertTrue(view.count("pre.mermaid svg g.node") >= 9, "the event and the build job's eight steps")
        val text = view.diagramText()
        for (part in listOf("push", "pull_request", "Build (\${{ matrix.os }})", "actions/checkout@v6")) {
            assertTrue(part in text, "$part in $text")
        }
        assertTrue(checkNotNull(view.zoom).node.isVisible)

        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.webView.engine.awaitTrue("document.body.classList.contains('dark')")
        view.webView.engine.awaitDrawing()
        assertEquals(null, view.webView.engine.mermaidError())
        assertEquals(1, view.count("pre.mermaid svg"))
        assertEquals(1, parsed.size, "the theme changes in the page, the workflow is not parsed again")
    }

    @Test
    fun `an edited and saved workflow is drawn again`(robot: FxRobot) {
        val (_, view) = show(robot, "ci.yml")
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        view.awaitGraph()
        assertFalse("deploy" in view.diagramText())

        val added = "  deploy:\n    needs: build\n    runs-on: ubuntu-latest\n    steps:\n      - run: ./deploy.sh\n"
        robot.interact {
            view.sourceArea.replaceText(view.sourceArea.length, view.sourceArea.length, added)
            view.save()
        }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            view.script("document.querySelector('pre.mermaid svg') !== null") == true && "deploy" in view.diagramText()
        }
        view.webView.engine.awaitDrawing()
        assertEquals(null, view.webView.engine.mermaidError())
        assertTrue("./deploy.sh" in view.diagramText())
    }

    @Test
    fun `a workflow with errors only shows a note`(robot: FxRobot) {
        val file = workflowsFolder().resolve("broken.yml")
        WorkflowFiles.fixture("broken-syntax.yml").copyTo(file)
        val view = show(robot) { WorkflowFileViewer().open(file) as PreviewFileView }
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            view.note().let { it is String && it != WorkflowPreview.READING }
        }
        val note = view.note() as String
        assertTrue("Missing closing" in note, note)
        assertEquals(0, view.count("pre.mermaid"))
    }

    @Test
    fun `the graphs of the repository's workflows and of the parser's fixtures draw without a Mermaid error`(
        robot: FxRobot,
    ) {
        val webView = WaitForAsyncUtils.asyncFx<WebView> { WebView() }.get()
        robot.interact { container.children.setAll(webView) }
        val engine = webView.engine
        var drawn = 0
        for (file in WorkflowFiles.repository + WorkflowFiles.fixtures) {
            val graph = WorkflowGraphs.of(file.name, file.readText(), WebViewWorkflowParser.shared)
            if (graph.isEmpty) continue
            robot.interact { engine.loadContent(WorkflowPreview.render(graph, dark = false)) }
            awaitLoaded(engine)
            engine.awaitDrawing()
            assertEquals(null, engine.mermaidError(), "${file.name}:\n${graph.mermaid}")
            assertEquals(1, engine.count("pre.mermaid svg"), file.name)
            drawn++
        }
        assertTrue(drawn >= 11, "drawn $drawn")
    }
}
