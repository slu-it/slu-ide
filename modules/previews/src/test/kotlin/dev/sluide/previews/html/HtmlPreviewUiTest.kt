package dev.sluide.previews.html

import com.sun.net.httpserver.HttpServer
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.previews.preview.PreviewFileView
import javafx.concurrent.Worker
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ToggleButton
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTML preview with a real WebView. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class HtmlPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private val page =
        """
        |<html><head><title>Static</title><link rel="stylesheet" href="style.css"></head>
        |<body><h1 id="top">Hello</h1><a href="other.html">other</a> <a href="https://example.com/">web</a>
        |<script>document.title = 'Scripted'</script></body></html>
        |
        """.trimMargin()

    private fun show(robot: FxRobot): Pair<Path, PreviewFileView> {
        dir.resolve("style.css").writeText("h1 { color: rgb(255, 0, 0); }")
        dir.resolve("other.html").writeText("<h1>Other</h1>")
        val file = dir.resolve("index.html").apply { writeText(page) }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { HtmlFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        awaitPage(view, "Hello")
        return file to view
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    /** Waits until the preview shows its file, completely loaded, with an `h1` of [heading]. */
    private fun awaitPage(
        view: PreviewFileView,
        heading: String,
    ) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx {
                val engine = view.webView.engine
                engine.loadWorker.state == Worker.State.SUCCEEDED &&
                    engine.location
                        .orEmpty()
                        .substringBefore('#')
                        .endsWith("index.html") &&
                    engine.document
                        ?.getElementsByTagName("h1")
                        ?.item(0)
                        ?.textContent == heading
            }
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** A mouse press in the preview, as before a click on a link. */
    private fun click(
        robot: FxRobot,
        view: PreviewFileView,
    ) = robot.interact {
        Event.fireEvent(
            view.webView,
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null,
            ),
        )
    }

    @Test
    fun `shows the page from disk with its relative styles, without running its scripts`(robot: FxRobot) {
        val (_, view) = show(robot)

        assertEquals("Static", fx { view.webView.engine.title })
        assertEquals(
            "rgb(255, 0, 0)",
            fx { view.webView.engine.executeScript("getComputedStyle(document.querySelector('h1')).color") },
        )
    }

    @Test
    fun `the toolbar lets the page's scripts run, after a warning`(robot: FxRobot) {
        val (_, view) = show(robot)
        val toggle = robot.lookup(".preview-scripts").queryAs(ToggleButton::class.java)
        assertTrue(!toggle.isSelected)

        var loads = 0
        robot.interact {
            view.webView.engine.loadWorker.stateProperty().subscribe { state ->
                if (state == Worker.State.RUNNING) loads++
            }
        }
        view.confirmScripts = { false }
        robot.interact { toggle.fire() }
        assertTrue(!toggle.isSelected)
        assertEquals("Static", fx { view.webView.engine.title })
        assertEquals(0, loads, "cancelling the warning loads nothing")

        view.confirmScripts = { true }
        robot.interact { toggle.fire() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { view.webView.engine.title } == "Scripted" }

        robot.interact { toggle.fire() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { view.webView.engine.title } == "Static" }
    }

    /** Records what the view asks of the IDE. */
    private class RecordingHost(
        override val projectFolder: Path? = null,
    ) : FileViewHost {
        val files = mutableListOf<Path>()
        val pages = mutableListOf<URI>()

        override fun openFile(file: Path): Boolean = files.add(file)

        override fun openWebPage(uri: URI) {
            pages += uri
        }
    }

    @Test
    fun `leaving the page opens the target through the host and shows the file again`(robot: FxRobot) {
        val (file, view) = show(robot)
        val host = RecordingHost()
        view.host = host

        // What a click on a link does: the user presses the mouse, then the page navigates.
        click(robot, view)
        robot.interact { view.webView.engine.load(PreviewServer.url(dir, dir.resolve("other.html")).toString()) }
        awaitPage(view, "Hello")
        click(robot, view)
        robot.interact { view.webView.engine.load(dir.resolve("style.css").toUri().toString()) }
        awaitPage(view, "Hello")
        click(robot, view)
        robot.interact { view.webView.engine.load("https://example.com/") }
        awaitPage(view, "Hello")
        // An anchor on the page itself stays.
        robot.interact { view.webView.engine.load(PreviewServer.url(dir, file).toString() + "#top") }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx {
                view.webView.engine.location
                    .endsWith("#top")
            }
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(dir.resolve("other.html"), dir.resolve("style.css")), host.files)
        assertEquals(listOf(URI("https://example.com/")), host.pages)
        assertTrue(!robot.lookup(".preview-notice").query<Node>().isVisible)
    }

    @Test
    fun `a link clicked in the page opens its file and the page stays`(robot: FxRobot) {
        val (_, view) = show(robot)
        val host = RecordingHost()
        view.host = host

        // The press a click starts with, then WebKit following the link: no navigation of ours.
        click(robot, view)
        robot.interact { view.webView.engine.executeScript("document.querySelector('a').click()") }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { host.files.isNotEmpty() } }
        awaitPage(view, "Hello")

        assertEquals(listOf(dir.resolve("other.html")), fx { host.files.toList() })
        assertTrue(!robot.lookup(".preview-notice").query<Node>().isVisible)
    }

    @Test
    fun `a page that navigates on its own is stopped, not reloaded in a loop`(robot: FxRobot) {
        dir.resolve("other.html").writeText("<h1>Other</h1>")
        val file =
            dir.resolve("index.html").apply {
                writeText("<meta http-equiv=\"refresh\" content=\"0;url=other.html\"><h1>Hello</h1>")
            }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { HtmlFileViewer().open(file) as PreviewFileView }.get()
        val host = RecordingHost()
        view.host = host
        robot.interact { container.children.setAll(view.node) }

        WaitForAsyncUtils.waitFor(
            10,
            TimeUnit.SECONDS,
        ) { fx { robot.lookup(".preview-notice").query<Node>().isVisible } }
        WaitForAsyncUtils.sleep(200, TimeUnit.MILLISECONDS)

        assertTrue("other.html" in view.notice.text)
        assertEquals(emptyList(), host.files)
        // Changing the file shows it again.
        file.writeText(page)
        robot.interact { view.reload() }
        awaitPage(view, "Hello")
        assertTrue(!robot.lookup(".preview-notice").query<Node>().isVisible)
    }

    /** A page in `project/site` whose style lies above it, as on Javadoc and test-report pages. */
    private fun projectPage(script: String = ""): Path {
        val project = dir.resolve("project")
        project
            .resolve("css")
            .createDirectories()
            .resolve("style.css")
            .writeText("h1 { color: rgb(255, 0, 0); }")
        return project.resolve("site").createDirectories().resolve("index.html").apply {
            writeText("<link rel=\"stylesheet\" href=\"../css/style.css\"><h1>Hello</h1><pre id=\"out\"></pre>$script")
        }
    }

    private fun color(view: PreviewFileView) =
        fx { view.webView.engine.executeScript("getComputedStyle(document.querySelector('h1')).color") }

    @Test
    fun `the page may use the files of its project, once it knows the project`(robot: FxRobot) {
        val file = projectPage()
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { HtmlFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        awaitPage(view, "Hello")
        // Without a host the page has only its own folder.
        assertEquals("rgb(0, 0, 0)", color(view))

        robot.interact { view.host = RecordingHost(projectFolder = dir.resolve("project")) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { color(view) == "rgb(255, 0, 0)" }
    }

    @Test
    fun `the page's scripts read the project's files, but no others`(robot: FxRobot) {
        val project = dir.resolve("project")
        dir.resolve("outside.txt").writeText("OUTSIDE")
        val probes =
            listOf(
                "data.json",
                "../css/style.css",
                "../../outside.txt",
                "/../outside.txt",
                "../.env",
                "../out.txt",
                dir.resolve("outside.txt").toUri().toString(),
            )
        val script =
            """
            <script>
            var lines = ${probes.joinToString(",", "[", "]") { "'$it'" }}.map(function (url) {
                try {
                    var request = new XMLHttpRequest();
                    request.open('GET', url, false);
                    request.send();
                    return url + ' ' + request.status + ' ' + request.responseText.length;
                } catch (e) {
                    return url + ' failed';
                }
            });
            document.getElementById('out').textContent = lines.join('\n');
            </script>
            """.trimIndent()
        val file = projectPage(script)
        file.resolveSibling("data.json").writeText("{}")
        project.resolve(".env").writeText("SECRET")
        Files.createSymbolicLink(project.resolve("out.txt"), dir.resolve("outside.txt"))
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { HtmlFileViewer().open(file) as PreviewFileView }.get()
        view.confirmScripts = { true }
        robot.interact {
            view.host = RecordingHost(projectFolder = project)
            container.children.setAll(view.node)
        }
        robot.interact { view.scripts.set(true) }

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { view.webView.engine.executeScript("document.getElementById('out').textContent") } != ""
        }
        val results = fx { view.webView.engine.executeScript("document.getElementById('out').textContent") as String }
        assertEquals(
            listOf(
                "data.json 200 2",
                "../css/style.css 200 29",
                "../../outside.txt 404 0",
                "/../outside.txt 404 0",
                "../.env 404 0",
                "../out.txt 404 0",
                "${dir.resolve("outside.txt").toUri()} failed",
            ),
            results.lines(),
        )
    }

    /** Opens [file] as the editor area does: hosted right after it is created, with [project] as project folder. */
    private fun open(
        robot: FxRobot,
        file: Path,
        project: Path? = null,
        host: RecordingHost = RecordingHost(projectFolder = project),
    ): PreviewFileView {
        val view =
            WaitForAsyncUtils
                .asyncFx<PreviewFileView> {
                    (HtmlFileViewer().open(file) as PreviewFileView).also { it.host = host }
                }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    private fun awaitColor(view: PreviewFileView) =
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { color(view) == "rgb(255, 0, 0)" }

    @Test
    fun `pages in hidden folders and behind symbolic links are shown with their neighbours`(robot: FxRobot) {
        val project = dir.resolve("project").createDirectories()
        val style = "h1 { color: rgb(255, 0, 0); }"
        val page = "<link rel=\"stylesheet\" href=\"style.css\"><h1>Hello</h1>"
        val github = project.resolve(".github").createDirectories()
        github.resolve("style.css").writeText(style)
        github.resolve("page.html").writeText(page)
        val shared = dir.resolve("shared").createDirectories()
        shared.resolve("style.css").writeText(style)
        shared.resolve("page.html").writeText(page)
        Files.createSymbolicLink(project.resolve("docs"), shared)

        awaitColor(open(robot, github.resolve("page.html"), project))
        awaitColor(open(robot, project.resolve("docs/page.html"), project))
    }

    @Test
    fun `links that climb out of the served folder open their files`(robot: FxRobot) {
        val file = projectPage()
        val outside = dir.resolve("outside.html").apply { writeText("<h1>Outside</h1>") }
        val host = RecordingHost(projectFolder = dir.resolve("project"))
        val view = open(robot, file, host = host)
        awaitPage(view, "Hello")

        click(robot, view)
        // What WebKit makes of `../../outside.html` on the page.
        val link = PreviewServer.url(dir.resolve("project"), file).resolve("../../outside.html")
        robot.interact { view.webView.engine.load(link.toString()) }
        awaitPage(view, "Hello")

        assertEquals(listOf(outside), host.files)
        assertEquals(emptyList(), host.pages)
    }

    @Test
    fun `a page that reloads itself with a query is stopped`(robot: FxRobot) {
        val file =
            dir.resolve("index.html").apply {
                writeText("<meta http-equiv=\"refresh\" content=\"0;url=?r\"><h1>Hello</h1>")
            }
        val view = open(robot, file)

        WaitForAsyncUtils.waitFor(
            10,
            TimeUnit.SECONDS,
        ) { fx { robot.lookup(".preview-notice").query<Node>().isVisible } }
        assertTrue("?r" in view.notice.text)
    }

    @Test
    fun `a page that reloads itself in place does not reload forever`(robot: FxRobot) {
        val file =
            dir
                .resolve(
                    "index.html",
                ).apply { writeText("<meta http-equiv=\"refresh\" content=\"0\"><h1>Hello</h1>") }
        val view = open(robot, file)
        var loads = 0
        robot.interact {
            view.webView.engine.loadWorker.stateProperty().subscribe { state ->
                if (state ==
                    Worker.State.SUCCEEDED
                ) {
                    loads++
                }
            }
        }

        WaitForAsyncUtils.waitFor(
            10,
            TimeUnit.SECONDS,
        ) { fx { robot.lookup(".preview-notice").query<Node>().isVisible } }
        WaitForAsyncUtils.sleep(300, TimeUnit.MILLISECONDS)

        assertTrue(loads <= 2, "loaded $loads times")
        assertTrue(robot.lookup(".preview-notice").query<Node>().isVisible)
        assertTrue("reload itself" in view.notice.text)
    }

    @Test
    fun `requests to other sites do not reveal the page's address`(robot: FxRobot) {
        val referers = mutableListOf<String?>()
        val other = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        other.createContext("/") { exchange ->
            synchronized(referers) { referers += exchange.requestHeaders.getFirst("Referer") }
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        other.start()
        try {
            val file =
                dir.resolve("index.html").apply {
                    writeText("<h1>Hello</h1><img src=\"http://localhost:${other.address.port}/pixel.png\">")
                }
            open(robot, file)

            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { synchronized(referers) { referers.isNotEmpty() } }
            assertEquals(listOf<String?>(null), synchronized(referers) { referers.toList() })
        } finally {
            other.stop(0)
        }
    }

    @Test
    fun `reload keeps the scroll position`(robot: FxRobot) {
        val tall = "<div style=\"height: 5000px\"></div>"
        val file = dir.resolve("index.html").apply { writeText("<h1>Hello</h1>$tall") }
        val view = open(robot, file)
        awaitPage(view, "Hello")
        fx { view.webView.engine.executeScript("window.scrollTo(0, 700)") }

        file.writeText("<h1>Changed</h1>$tall")
        robot.interact { view.reload() }
        awaitPage(view, "Changed")

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) {
            fx { (view.webView.engine.executeScript("window.scrollY") as Number).toDouble() } == 700.0
        }
    }

    @Test
    fun `a moved page is shown from its new path and keeps the scroll position`(robot: FxRobot) {
        val tall = "<div style=\"height: 5000px\"></div>"
        val site = dir.resolve("site").createDirectories()
        val file = site.resolve("index.html").apply { writeText("<h1>Hello</h1>$tall") }
        val view = open(robot, file)
        awaitPage(view, "Hello")
        fx { view.webView.engine.executeScript("window.scrollTo(0, 700)") }
        val moved = dir.resolve("web")
        site.moveTo(moved)

        robot.interact { view.moved(moved.resolve("index.html")) }

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx {
                view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED &&
                    PreviewServer.fileFor(
                        view.webView.engine.location
                            .orEmpty(),
                    ) == moved.resolve("index.html") &&
                    (view.webView.engine.executeScript("window.scrollY") as Number).toDouble() == 700.0
            }
        }
    }

    @Test
    fun `reload shows the changed file`(robot: FxRobot) {
        val (file, view) = show(robot)

        file.writeText(page.replace("Hello", "Changed"))
        robot.interact { view.reload() }

        awaitPage(view, "Changed")
    }
}
