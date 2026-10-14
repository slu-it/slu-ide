package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Markdown view with a real WebView. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MarkdownFileViewUiTest {
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
        markdown: String,
    ): Pair<Path, MarkdownFileView> {
        val file = dir.resolve("README.md").apply { writeText(markdown) }
        // A WebView must be created on the FX thread, as the editor area does.
        val view =
            WaitForAsyncUtils
                .asyncFx<MarkdownFileView> {
                    MarkdownFileViewer().open(
                        file,
                    ) as MarkdownFileView
                }.get()
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view)
        return file to view
    }

    private fun awaitLoaded(view: MarkdownFileView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.preview.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun MarkdownFileView.script(code: String): Any? =
        WaitForAsyncUtils
            .asyncFx<Any?> {
                preview.engine.executeScript(code)
            }.get()

    private fun FxRobot.modeButton(mode: MarkdownMode) =
        lookup(".markdown-mode").queryAllAs(ToggleButton::class.java).single { it.text == mode.label }

    @Test
    fun `shows the highlighted source beside the rendered preview`(robot: FxRobot) {
        val (_, view) = show(robot, "# Hello *world*\n\n| a |\n|---|\n| 1 |\n")

        val split = robot.lookup(".split-pane").queryAs(SplitPane::class.java)
        assertEquals<List<Node>>(listOf(view.source.node, view.preview), split.items)
        assertTrue(robot.modeButton(MarkdownMode.SPLIT).isSelected)
        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        assertTrue("keyword" in area.getStyleOfChar(1))
        assertEquals("Hello world", view.script("document.querySelector('h1').textContent"))
        assertEquals("1", view.script("document.querySelector('td').textContent"))
    }

    @Test
    fun `the toolbar shows the source or the preview alone`(robot: FxRobot) {
        val (_, view) = show(robot, "# Title\n")
        val split = robot.lookup(".split-pane").queryAs(SplitPane::class.java)

        robot.interact { robot.modeButton(MarkdownMode.PREVIEW).fire() }
        assertEquals<List<Node>>(listOf(view.preview), split.items)
        assertEquals(MarkdownMode.PREVIEW, view.mode.get())

        robot.interact { robot.modeButton(MarkdownMode.SOURCE).fire() }
        assertEquals<List<Node>>(listOf(view.source.node), split.items)

        // Clicking the selected mode again keeps it.
        robot.interact { robot.modeButton(MarkdownMode.SOURCE).fire() }
        assertTrue(robot.modeButton(MarkdownMode.SOURCE).isSelected)
        assertEquals<List<Node>>(listOf(view.source.node), split.items)

        robot.interact { robot.modeButton(MarkdownMode.SPLIT).fire() }
        assertEquals<List<Node>>(listOf(view.source.node, view.preview), split.items)
    }

    @Test
    fun `reload updates source and preview`(robot: FxRobot) {
        val (file, view) = show(robot, "# Before\n")

        file.writeText("# After\n")
        robot.interact { view.reload() }
        awaitLoaded(view)

        assertEquals("# After\n", robot.lookup(".code-area").queryAs(CodeArea::class.java).text)
        assertEquals("After", view.script("document.querySelector('h1').textContent"))
    }

    @Test
    fun `the preview follows the colour scheme of the window`(robot: FxRobot) {
        val (_, view) = show(robot, "# Title\n")

        fun darkAfter(scheme: ColorScheme): Any? {
            robot.interact { container.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            return view.script("document.body.classList.contains('dark')")
        }

        assertEquals(true, darkAfter(ColorScheme.DARK))
        assertEquals(false, darkAfter(ColorScheme.LIGHT))
    }

    /** Paragraph `i` (1-based) is on source line `2 * (i - 1)`. */
    private val longDocument = (1..200).joinToString("\n\n") { "Paragraph $it" } + "\n"

    /** How far the block starting at source [line] is from the top of the preview, in pixels. */
    private fun MarkdownFileView.offsetOf(line: Int) =
        (script("document.querySelector('[data-line=\"$line\"]').getBoundingClientRect().top") as Number).toDouble()

    @Test
    fun `a long document opens at its top`(robot: FxRobot) {
        val (_, view) = show(robot, longDocument)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(0, view.source.area.firstVisibleParToAllParIndex())
        assertEquals(0.0, (view.script("window.scrollY") as Number).toDouble())
    }

    @Test
    fun `side by side, the preview scrolls with the source`(robot: FxRobot) {
        val (_, view) = show(robot, longDocument)

        robot.interact { view.source.area.showParagraphAtTop(100) }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue((view.script("window.scrollY") as Number).toDouble() > 100.0)
        assertEquals(0.0, view.offsetOf(100), 5.0)
    }

    @Test
    fun `after a reload the preview keeps its place`(robot: FxRobot) {
        val (file, view) = show(robot, longDocument)
        robot.interact { view.source.area.showParagraphAtTop(60) }
        WaitForAsyncUtils.waitForFxEvents()

        file.writeText(longDocument + "\nAdded\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        assertEquals(0.0, view.offsetOf(60), 5.0)

        // Shown alone, the preview goes back to its own scroll position.
        robot.interact { view.mode.set(MarkdownMode.PREVIEW) }
        view.script("window.scrollTo(0, 700)")
        file.writeText(longDocument + "\nChanged\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        assertEquals(700.0, (view.script("window.scrollY") as Number).toDouble(), 1.0)
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
    fun `clicked links open through the host and never inside the preview`(robot: FxRobot) {
        val (_, view) =
            show(
                robot,
                "# Title\n\n[web](https://example.com/x) [doc](docs/other.md#part) [here](#title)\n",
            )
        val host = RecordingHost()
        view.host = host
        val page = view.script("location.href")

        for (i in 0..2) view.script("document.querySelectorAll('a')[$i].click()")
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(URI("https://example.com/x")), host.pages)
        assertEquals(listOf(dir.resolve("docs/other.md")), host.files)
        assertEquals(page, view.script("location.href"))
    }

    @Test
    fun `anchors scroll within the preview`(robot: FxRobot) {
        val (_, view) =
            show(
                robot,
                "[last](#paragraph-200)\n\n" + longDocument.replace("Paragraph 200", "## Paragraph 200"),
            )
        robot.interact { view.mode.set(MarkdownMode.PREVIEW) }

        view.script("document.querySelector('a').click()")
        // A navigation would take a moment to start; give it the chance.
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("", WaitForAsyncUtils.asyncFx<String?> { view.preview.engine.location }.get().orEmpty())
        assertTrue((view.script("window.scrollY") as Number).toDouble() > 1000.0)
        assertEquals("Paragraph 200", view.script("document.getElementById('paragraph-200').textContent"))
    }

    @Test
    fun `a preview that navigated away shows the Markdown again`(robot: FxRobot) {
        val (_, view) = show(robot, "# Mine\n")
        val other = dir.resolve("other.html").apply { writeText("<h1>Other</h1>") }

        val locations = mutableListOf<String?>()
        robot.interact {
            view.preview.engine
                .locationProperty()
                .addListener { _, _, location -> locations += location }
            view.preview.engine.load(other.toUri().toString())
        }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils
                .asyncFx<Boolean> {
                    view.preview.engine.location
                        .isNullOrEmpty()
                }.get() &&
                view.script("document.querySelector('h1') && document.querySelector('h1').textContent") == "Mine"
        }
        assertTrue(locations.any { it.orEmpty().endsWith("other.html") }, "navigated away: $locations")
        assertEquals("function", view.script("typeof sluideBridge.open"))
    }

    @Test
    fun `the divider keeps its place when switching modes`(robot: FxRobot) {
        val (_, view) = show(robot, "# Title\n")
        val split = robot.lookup(".split-pane").queryAs(SplitPane::class.java)

        robot.interact { split.setDividerPositions(0.3) }
        robot.interact { view.mode.set(MarkdownMode.PREVIEW) }
        robot.interact { view.mode.set(MarkdownMode.SPLIT) }

        assertEquals(0.3, split.dividerPositions[0], 0.01)
    }

    @Test
    fun `diffs of Markdown files are highlighted like the source`(robot: FxRobot) {
        val file = dir.resolve("README.md").apply { writeText("# Title\n") }
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(MarkdownFileViewer()), fallback)

        val diff = assertNotNull(viewers.openDiff(file, TextDiff("# Old\n", listOf(LineChange(1..1, 1..1)))))
        robot.interact { container.children.setAll(diff.node) }
        WaitForAsyncUtils.waitForFxEvents()

        val areas = robot.lookup(".code-area").queryAllAs(CodeArea::class.java)
        assertTrue(areas.all { "keyword" in it.getStyleOfChar(1) })
    }
}
