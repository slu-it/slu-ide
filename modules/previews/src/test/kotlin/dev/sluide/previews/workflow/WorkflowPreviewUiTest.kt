package dev.sluide.previews.workflow

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.githubactions.RemoteFile
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteSources
import dev.sluide.githubactions.RemoteText
import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.WorkflowGraphs
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.previews.markdown.MermaidFileViewer
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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
    fun `local reusable workflows and composite actions are drawn nested`(robot: FxRobot) {
        val workflows = workflowsFolder()
        workflows.resolve("build.yml").writeText(
            """
            on:
              workflow_call:
                inputs:
                  java:
                    type: string
            jobs:
              compile:
                runs-on: ubuntu-latest
                steps:
                  - name: Compile inside
                    run: ./gradlew assemble
            """.trimIndent(),
        )
        dir.resolve(".github/actions/setup").createDirectories().resolve("action.yml").writeText(
            """
            name: Set up
            runs:
              using: composite
              steps:
                - name: Install inside
                  run: ./install.sh
                  shell: bash
            """.trimIndent(),
        )
        val file = workflows.resolve("ci.yml")
        file.writeText(
            """
            on: push
            jobs:
              build:
                uses: ./.github/workflows/build.yml
                with:
                  java: 21
              test:
                needs: build
                runs-on: ubuntu-latest
                steps:
                  - uses: ./.github/actions/setup
                    with:
                      who: me
                  - run: ./gradlew test
            """.trimIndent(),
        )
        val view = show(robot) { WorkflowFileViewer().open(file) as PreviewFileView }
        robot.interact { view.mode.set(PreviewMode.SPLIT) }

        view.awaitGraph()
        assertEquals(null, view.webView.engine.mermaidError())
        assertEquals(null, view.script("var p = document.querySelector('.diagram-note'); p && p.textContent"))
        val text = view.diagramText()
        for (part in listOf(
            "calls build.yml",
            "java: 21",
            "Compile inside",
            "Set up",
            "<composite>",
            "who: me",
            "Install inside",
            "gradlew test",
        )) {
            assertTrue(part in text, "$part in $text")
        }
    }

    /**
     * A workflow whose steps have inputs: steps of a job, an expanded composite and its steps, and a job calling a
     * reusable workflow, which is expanded.
     */
    private fun inputsWorkflow(): Path {
        dir.resolve(".github/actions/build").createDirectories().resolve("action.yml").writeText(
            """
            name: Build
            runs:
              using: composite
              steps:
                - uses: actions/cache@v4
                  with:
                    path: ~/.gradle
                    key: gradle
                - name: Gradle
                  uses: gradle/actions/setup-gradle@v4
                  with:
                    arguments: build
                - run: ls
                  shell: bash
            """.trimIndent(),
        )
        workflowsFolder().resolve("deploy.yml").writeText(
            """
            on:
              workflow_call:
                inputs:
                  environment:
                    type: string
            jobs:
              deploy:
                runs-on: ubuntu-latest
                steps:
                  - run: ./deploy.sh
            """.trimIndent(),
        )
        val file = workflowsFolder().resolve("ci.yml")
        file.writeText(
            """
            on: [push, pull_request]
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                    with:
                      fetch-depth: 0
                  - name: Set up Java
                    uses: actions/setup-java@v4
                    with:
                      java-version: 21
                      distribution: temurin
                  - uses: ./.github/actions/build
                    with:
                      tasks: build test
                  - run: echo done
              deploy:
                needs: build
                uses: ./.github/workflows/deploy.yml
                with:
                  environment: prod
            """.trimIndent(),
        )
        return file
    }

    /**
     * The screen box (left, right, top, bottom) of what [select] (a script expression with `svg`, the diagram, and
     * `node(id)` and `cluster(id)`, its node and subgraph of a Mermaid id) gives.
     */
    private fun PreviewFileView.box(select: String): List<Double> {
        val box =
            script(
                """
                (function () {
                  var svg = document.querySelector('pre.mermaid svg');
                  function node(id) {
                    return Array.prototype.filter.call(svg.querySelectorAll('g.node'), function (n) {
                      return new RegExp('-flowchart-' + id + '-\\d+$').test(n.id);
                    })[0];
                  }
                  function cluster(id) { return svg.querySelector('g.cluster[id="' + svg.id + '-' + id + '"]'); }
                  var r = ($select).getBoundingClientRect();
                  return [r.left, r.right, r.top, r.bottom].join(' ');
                })()
                """,
            ) as String
        return box.split(" ").map { it.toDouble() }
    }

    /** Whether [inner] (left, right, top, bottom) lies within [outer], give or take a pixel. */
    private fun within(
        inner: List<Double>,
        outer: List<Double>,
    ) = inner[0] >= outer[0] - 1 && inner[1] <= outer[1] + 1 && inner[2] >= outer[2] - 1 && inner[3] <= outer[3] + 1

    @Test
    fun `inputs are framed in their step's box and boxed at the top of an expansion, in both colour schemes`(
        robot: FxRobot,
    ) {
        val view = show(robot) { WorkflowFileViewer().open(inputsWorkflow()) as PreviewFileView }
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }

        for (dark in listOf(false, true)) {
            if (dark) {
                robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
                view.webView.engine.awaitTrue("document.body.classList.contains('dark')")
            }
            view.awaitGraph()
            assertEquals(null, view.webView.engine.mermaidError())

            for (step in listOf("j0s0", "j0s1", "j0s2s0", "j0s2s1")) {
                val frame = view.box("node('$step').querySelector('foreignObject div.sluide-inputs')")
                val shape = view.box("node('$step').querySelector('rect')")
                assertTrue(within(frame, shape), "$step: inputs $frame in its box $shape")
            }
            assertEquals(4, view.count("pre.mermaid svg div.sluide-inputs"))

            // The composite's inputs box: in its subgraph, below the title, above its steps.
            val composite = view.box("cluster('j0s2')")
            val inputs = view.box("node('j0s2i')")
            assertTrue(within(inputs, composite), "j0s2i $inputs in j0s2 $composite")
            assertTrue(
                inputs[2] >= view.box("cluster('j0s2').querySelector('.cluster-label')")[3] - 1,
                "below the title",
            )
            for (step in listOf("j0s2s0", "j0s2s1", "j0s2s2")) {
                assertTrue(inputs[3] <= view.box("node('$step')")[2], "j0s2i $inputs above $step")
            }

            // The called workflow's inputs box: in its subgraph, above its job.
            val called = view.box("cluster('j1w')")
            val callInputs = view.box("node('j1wi')")
            assertTrue(within(callInputs, called), "j1wi $callInputs in j1w $called")
            assertTrue(callInputs[2] >= view.box("cluster('j1w').querySelector('.cluster-label')")[3] - 1)
            assertTrue(callInputs[3] <= view.box("cluster('j1wj0')")[2], "j1wi $callInputs above job j1wj0")
        }
    }

    @Test
    fun `a click on a linked box opens its source at its line, a click elsewhere still zooms`(robot: FxRobot) {
        val action = dir.resolve(".github/actions/setup").createDirectories().resolve("action.yml")
        action.writeText(
            "name: Set up\nruns:\n  using: composite\n  steps:\n    - name: Install inside\n      run: x\n" +
                "      shell: bash\n",
        )
        val file = workflowsFolder().resolve("ci.yml")
        file.writeText(
            "on: push\njobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n" +
                "      - uses: ./.github/actions/setup\n      - run: ./gradlew test\n",
        )
        val opened = CopyOnWriteArrayList<Pair<Path, Int>>()
        val host =
            object : FileViewHost {
                override fun openFile(file: Path) = false

                override fun openFile(
                    file: Path,
                    line: Int,
                ): Boolean {
                    opened += file to line
                    return true
                }

                override fun openWebPage(uri: URI) {}
            }
        val view = show(robot) { (WorkflowFileViewer().open(file) as PreviewFileView).also { it.host = host } }
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.awaitGraph()
        val linked =
            "Array.prototype.map.call(document.querySelectorAll('pre.mermaid svg .linked'), " +
                "function (e) { return e.getAttribute('data-node-link'); }).sort().join(' ')"
        // The job's title, the composite action's title and its step, the run step; not the trigger.
        assertEquals("j0 j0s0 j0s0s0 j0s1", view.script(linked))
        val zoom = checkNotNull(view.zoom)
        val fitted = WaitForAsyncUtils.asyncFx<Boolean> { zoom.fit }.get()

        view.script(
            "document.querySelector('[data-node-link=\"j0s0s0\"] .nodeLabel')" +
                ".dispatchEvent(new MouseEvent('click', { bubbles: true }))",
        )
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(action.toAbsolutePath().normalize() to 4), opened.toList(), "0-based")
        assertEquals(fitted, WaitForAsyncUtils.asyncFx<Boolean> { zoom.fit }.get(), "the zoom stays")

        // A subgraph's title: the composite action's `uses:` in the workflow.
        view.script(
            "document.querySelector('[data-node-link=\"j0s0\"] .nodeLabel')" +
                ".dispatchEvent(new MouseEvent('click', { bubbles: true }))",
        )
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(file.toAbsolutePath().normalize() to 5, opened.last())
        assertEquals(fitted, WaitForAsyncUtils.asyncFx<Boolean> { zoom.fit }.get(), "the zoom stays")

        view.script(
            "document.querySelector('pre.mermaid svg').dispatchEvent(new MouseEvent('click', { bubbles: true }))",
        )
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(!fitted, WaitForAsyncUtils.asyncFx<Boolean> { zoom.fit }.get(), "a click elsewhere zooms")

        // The area of a linked subgraph, outside its title: zooms too.
        view.script(
            "document.querySelector('[data-node-link=\"j0s0\"]').parentNode.querySelector(':scope > rect')" +
                ".dispatchEvent(new MouseEvent('click', { bubbles: true }))",
        )
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(fitted, WaitForAsyncUtils.asyncFx<Boolean> { zoom.fit }.get(), "a subgraph's area zooms")
        assertEquals(2, opened.size)
    }

    @Test
    fun `nested graphs draw without a Mermaid error, also with cycle and limit boxes deep inside`(robot: FxRobot) {
        Files.walk(WorkflowFiles.nested).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach {
                val target = dir.resolve(WorkflowFiles.nested.relativize(it).toString())
                target.parent.createDirectories()
                it.copyTo(target)
            }
        }
        val workflows = workflowsFolder()
        for (level in 0..11) {
            val next = "./.github/workflows/deep${level + 1}.yml"
            workflows.resolve("deep$level.yml").writeText("on:\n  workflow_call:\njobs:\n  next:\n    uses: $next\n")
        }
        dir.resolve(".github/actions/loop").createDirectories().resolve("action.yml").writeText(
            """
            runs:
              using: composite
              steps:
                - run: echo before
                  shell: bash
                - uses: ./.github/actions/loop
            """.trimIndent(),
        )
        workflows.resolve("cycles.yml").writeText(
            """
            on: push
            jobs:
              main:
                runs-on: ubuntu-latest
                steps:
                  - uses: ./.github/actions/loop
                  - uses: ./.github/actions/setup
            """.trimIndent(),
        )
        val webView = WaitForAsyncUtils.asyncFx<WebView> { WebView() }.get()
        robot.interact { container.children.setAll(webView) }
        val engine = webView.engine
        val expected =
            mapOf(
                "caller.yml" to "class j1s2 unresolved",
                "deep0.yml" to "class ${"j0w".repeat(11)} limit",
                "cycles.yml" to "class j0s0s1 cycle",
            )

        for ((name, classes) in expected) {
            val file = workflows.resolve(name)
            val graph = WorkflowGraphs.of(name, file.readText(), WebViewWorkflowParser.shared, RepositoryFiles(dir))
            assertTrue(classes in graph.mermaid, "$name:\n${graph.mermaid}")
            robot.interact { engine.loadContent(WorkflowPreview.render(graph, dark = false)) }
            awaitLoaded(engine)
            engine.awaitDrawing()
            assertEquals(null, engine.mermaidError(), "$name:\n${graph.mermaid}")
            assertEquals(1, engine.count("pre.mermaid svg"), name)
        }
    }

    @Test
    fun `Fetch References shows only while the preview shows and draws the fetched remote action nested`(
        robot: FxRobot,
    ) {
        val file = workflowsFolder().resolve("remote.yml")
        file.writeText(
            "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: acme/setup@v1\n",
        )
        val composite =
            "name: Set up\nruns:\n  using: composite\n  steps:\n    - name: Install inside\n      run: x\n      " +
                "shell: bash\n    - uses: ./local\n"
        val remote = FakeRemoteSources(mapOf(RemoteFile("acme", "setup", "v1", "action.yml") to composite))
        val services = WindowServices().apply { register(RemoteSources::class.java, remote) }
        val host =
            object : FileViewHost {
                override fun openFile(file: Path) = false

                override fun openWebPage(uri: URI) {}

                override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
            }
        // As a tab does: the view renders once before it gets its host.
        val view = show(robot) { (WorkflowFileViewer().open(file) as PreviewFileView).also { it.host = host } }
        val button = view.actionButtons.single()
        assertEquals(WorkflowPreview.FETCH, button.text)
        assertFalse(button.isVisible, "not in Source")

        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        view.awaitGraph()
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { !button.isDisabled }
        assertTrue(button.isVisible)
        assertTrue("not fetched" in view.diagramText(), view.diagramText())

        robot.interact { button.fire() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            view.script("document.querySelector('pre.mermaid svg') !== null") == true &&
                "Install inside" in view.diagramText()
        }
        view.webView.engine.awaitDrawing()
        assertEquals(null, view.webView.engine.mermaidError())
        val text = view.diagramText()
        for (part in listOf("acme/setup@v1", "<composite>", "Install inside", "workspace-relative")) {
            assertTrue(part in text, "$part in $text")
        }
        assertEquals(listOf(false, true), remote.readers)
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { !button.isDisabled }

        robot.interact { view.mode.set(PreviewMode.SOURCE) }
        assertFalse(button.isVisible)
        val mermaid = dir.resolve("chart.mmd").apply { writeText("flowchart LR\n  a --> b\n") }
        val other = show(robot) { MermaidFileViewer().open(mermaid) as PreviewFileView }
        assertTrue(other.actionButtons.isEmpty(), "only the workflow preview has the button")
    }

    /** Remote files of [repository] (by file, the text), cached once a reader that may fetch read them. */
    private class FakeRemoteSources(
        private val repository: Map<RemoteFile, String>,
    ) : RemoteSources {
        private val cache = ConcurrentHashMap<RemoteFile, RemoteText>()

        /** The `network` of each reader made. */
        val readers = CopyOnWriteArrayList<Boolean>()

        override fun reader(
            network: Boolean,
            cancelled: () -> Boolean,
        ): RemoteReader {
            readers += network
            return RemoteReader { file ->
                if (network) {
                    val text = repository[file]?.let { RemoteText.Found(it, "c0ffee") } ?: RemoteText.Missing
                    cache[file] = text
                    text
                } else {
                    cache[file] ?: RemoteText.NotFetched
                }
            }
        }
    }

    @Test
    fun `Export Mermaid writes the graph shown beside the workflow, which the Mermaid viewer draws`(robot: FxRobot) {
        val (file, view) = show(robot, "ci.yml")
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        view.awaitGraph()
        val export = checkNotNull(view.export)
        val reports = CopyOnWriteArrayList<String>()
        export.report = { reports += it }

        WaitForAsyncUtils
            .asyncFx<Unit> {
                export.button.items
                    .single { it.text == "Mermaid" }
                    .fire()
            }.get()
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { !export.button.isDisable }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), reports)
        val mermaid = file.resolveSibling("ci.mmd").readText()
        assertTrue(mermaid.startsWith("flowchart LR\n"), mermaid)
        assertTrue("actions/checkout@v6" in mermaid, mermaid)

        val other = show(robot) { MermaidFileViewer().open(file.resolveSibling("ci.mmd")) as PreviewFileView }
        robot.interact { other.mode.set(PreviewMode.PREVIEW) }
        other.awaitGraph()
        assertEquals(null, other.webView.engine.mermaidError())
        assertTrue("actions/checkout@v6" in other.diagramText())
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
