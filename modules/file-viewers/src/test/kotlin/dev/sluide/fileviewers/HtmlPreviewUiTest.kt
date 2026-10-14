package dev.sluide.fileviewers

import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.control.ToggleButton
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
    fun `the toolbar lets the page's scripts run`(robot: FxRobot) {
        val (_, view) = show(robot)
        val toggle = robot.lookup(".preview-scripts").queryAs(ToggleButton::class.java)
        assertTrue(!toggle.isSelected)

        robot.interact { toggle.fire() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { view.webView.engine.title } == "Scripted" }

        robot.interact { toggle.fire() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { view.webView.engine.title } == "Static" }
    }

    /** Records what the view asks of the IDE. */
    private class RecordingHost : FileViewHost {
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

        // What a click on a link does: the page navigates.
        robot.interact { view.webView.engine.load(dir.resolve("other.html").toUri().toString()) }
        awaitPage(view, "Hello")
        robot.interact { view.webView.engine.load("https://example.com/") }
        awaitPage(view, "Hello")
        // An anchor on the page itself stays.
        robot.interact { view.webView.engine.load(file.toUri().toString() + "#top") }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(dir.resolve("other.html")), host.files)
        assertEquals(listOf(URI("https://example.com/")), host.pages)
    }

    @Test
    fun `reload shows the changed file`(robot: FxRobot) {
        val (file, view) = show(robot)

        file.writeText(page.replace("Hello", "Changed"))
        robot.interact { view.reload() }

        awaitPage(view, "Changed")
    }
}
