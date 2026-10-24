package dev.sluide.previews.preview

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.previews.html.HtmlFileViewer
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.notebook.NotebookFileViewer
import dev.sluide.previews.table.CookiesFileViewer
import dev.sluide.previews.table.CsvFileViewer
import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.layout.StackPane
import javafx.scene.web.WebEngine
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The preview view, with Markdown and a real WebView. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PreviewFileViewUiTest {
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
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve("README.md").apply { writeText(markdown) }
        // A WebView must be created on the FX thread, as the editor area does.
        val view =
            WaitForAsyncUtils
                .asyncFx<PreviewFileView> {
                    MarkdownFileViewer().open(
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
        WaitForAsyncUtils
            .asyncFx<Any?> {
                webView.engine.executeScript(code)
            }.get()

    private fun FxRobot.modeButton(mode: PreviewMode) =
        lookup(".preview-mode").queryAllAs(ToggleButton::class.java).single { it.text == mode.label }

    @Test
    fun `shows the highlighted source beside the rendered preview`(robot: FxRobot) {
        val (_, view) = show(robot, "# Hello *world*\n\n| a |\n|---|\n| 1 |\n")

        val split = robot.lookup(".split-pane").queryAs(SplitPane::class.java)
        assertEquals<List<Node>>(listOf(view.source.node, view.previewPane), split.items)
        assertTrue(robot.modeButton(PreviewMode.SPLIT).isSelected)
        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        assertTrue("keyword" in area.getStyleOfChar(1))
        assertEquals("Hello world", view.script("document.querySelector('h1').textContent"))
        assertEquals("1", view.script("document.querySelector('td').textContent"))
    }

    @Test
    fun `the toolbar shows the source or the preview alone`(robot: FxRobot) {
        val (_, view) = show(robot, "# Title\n")
        val split = robot.lookup(".split-pane").queryAs(SplitPane::class.java)

        robot.interact { robot.modeButton(PreviewMode.PREVIEW).fire() }
        assertEquals<List<Node>>(listOf(view.previewPane), split.items)
        assertEquals(PreviewMode.PREVIEW, view.mode.get())

        robot.interact { robot.modeButton(PreviewMode.SOURCE).fire() }
        assertEquals<List<Node>>(listOf(view.source.node), split.items)

        // Clicking the selected mode again keeps it.
        robot.interact { robot.modeButton(PreviewMode.SOURCE).fire() }
        assertTrue(robot.modeButton(PreviewMode.SOURCE).isSelected)
        assertEquals<List<Node>>(listOf(view.source.node), split.items)

        robot.interact { robot.modeButton(PreviewMode.SPLIT).fire() }
        assertEquals<List<Node>>(listOf(view.source.node, view.previewPane), split.items)
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
    fun `the source is edited and the preview renders it when saved`(robot: FxRobot) {
        val (file, view) = show(robot, "# Before\n")

        robot.interact { view.sourceArea.replaceText(2, 8, "After") }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(view.dirty.get())
        assertEquals("Before", view.script("document.querySelector('h1').textContent"), "unsaved edits do not render")

        robot.interact { view.save() }
        awaitLoaded(view)

        assertEquals("# After\n", file.readText())
        assertFalse(view.dirty.get())
        assertEquals("After", view.script("document.querySelector('h1').textContent"))
    }

    @Test
    fun `keeping unsaved edits over a change on disk renders the file's new text`(robot: FxRobot) {
        val (file, view) = show(robot, "# Before\n")
        robot.interact { view.sourceArea.replaceText(2, 8, "Mine") }
        file.writeText("# Theirs\n")
        robot.interact { view.reload() }
        assertEquals("Before", view.script("document.querySelector('h1').textContent"), "the conflict renders nothing")

        robot.interact {
            robot
                .lookup(".editor-banner .button")
                .queryAllAs(Button::class.java)
                .single { it.text == "Keep mine" }
                .fire()
        }
        awaitLoaded(view)

        assertEquals("Theirs", view.script("document.querySelector('h1').textContent"))
        assertEquals("# Mine\n", view.sourceArea.text)
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
    private fun PreviewFileView.offsetOf(line: Int) =
        (script("document.querySelector('[data-line=\"$line\"]').getBoundingClientRect().top") as Number).toDouble()

    @Test
    fun `a long document opens at its top`(robot: FxRobot) {
        val (_, view) = show(robot, longDocument)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(0, view.sourceArea.firstVisibleParToAllParIndex())
        assertEquals(0.0, (view.script("window.scrollY") as Number).toDouble())
    }

    @Test
    fun `side by side, the preview scrolls with the source`(robot: FxRobot) {
        val (_, view) = show(robot, longDocument)

        robot.interact { view.sourceArea.showParagraphAtTop(100) }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue((view.script("window.scrollY") as Number).toDouble() > 100.0)
        assertEquals(0.0, view.offsetOf(100), 5.0)
    }

    @Test
    fun `after a reload the preview keeps its place`(robot: FxRobot) {
        val (file, view) = show(robot, longDocument)
        robot.interact { view.sourceArea.showParagraphAtTop(60) }
        WaitForAsyncUtils.waitForFxEvents()

        file.writeText(longDocument + "\nAdded\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        assertEquals(0.0, view.offsetOf(60), 5.0)

        // Shown alone, the preview goes back to its own scroll position.
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        view.script("window.scrollTo(0, 700)")
        file.writeText(longDocument + "\nChanged\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        assertEquals(700.0, (view.script("window.scrollY") as Number).toDouble(), 1.0)
    }

    @Test
    fun `mermaid diagrams are drawn, again in the other colour scheme`(robot: FxRobot) {
        val (_, view) = show(robot, "# Flow\n\n```mermaid\ngraph LR\n  Start --> Stop\n```\n")
        val drawn = "document.querySelectorAll('pre.mermaid svg').length"

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { (view.script(drawn) as Number).toInt() == 1 }
        assertTrue((view.script("document.querySelector('pre.mermaid svg').textContent") as String).contains("Start"))

        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { (view.script(drawn) as Number).toInt() == 1 }
        assertTrue((view.script("document.querySelector('pre.mermaid svg').textContent") as String).contains("Stop"))
    }

    @Test
    fun `math is typeset by KaTeX`(robot: FxRobot) {
        val (_, view) = show(robot, "Inline \$a^2\$ and\n\n\$\$\n\\frac{1}{2}\n\$\$\n")

        assertEquals(2, (view.script("document.querySelectorAll('.math .katex').length") as Number).toInt())
        assertEquals(1, (view.script("document.querySelectorAll('.katex-display .mfrac').length") as Number).toInt())
        // The bundled fonts load (JavaFX's WebKit loads woff from data URIs, but not woff2).
        view.script(
            "window.fontsLoaded = null; document.fonts.load('16px KaTeX_Main')" +
                ".then(function (faces) { window.fontsLoaded = faces.length; }, function () { window.fontsLoaded = -1; })",
        )
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { view.script("window.fontsLoaded") != null }
        assertEquals(1, (view.script("window.fontsLoaded") as Number).toInt())
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
    fun `a moved file keeps its unsaved source, is saved at its new path and links from there`(robot: FxRobot) {
        val (file, view) = show(robot, "# Before\n\n[other](other.md)\n")
        val host = RecordingHost()
        view.host = host
        robot.interact { view.sourceArea.replaceText(2, 8, "After") }
        val moved = dir.resolve("docs").createDirectories().resolve("guide.md")
        file.moveTo(moved)

        robot.interact { assertTrue(view.moved(moved)) }
        awaitLoaded(view)

        assertTrue(view.dirty.get())
        assertEquals("Before", view.script("document.querySelector('h1').textContent"), "the saved text renders")
        robot.interact { view.save() }
        awaitLoaded(view)
        assertEquals("# After\n\n[other](other.md)\n", moved.readText())
        assertFalse(file.exists(), "nothing is written at the old path")
        assertEquals("After", view.script("document.querySelector('h1').textContent"))
        view.script("document.querySelector('a').click()")
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf(dir.resolve("docs/other.md")), host.files)
    }

    @Test
    fun `anchors scroll within the preview`(robot: FxRobot) {
        val (_, view) =
            show(
                robot,
                "[last](#paragraph-200)\n\n" + longDocument.replace("Paragraph 200", "## Paragraph 200"),
            )
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }

        view.script("document.querySelector('a').click()")
        // A navigation would take a moment to start; give it the chance.
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("", WaitForAsyncUtils.asyncFx<String?> { view.webView.engine.location }.get().orEmpty())
        assertTrue((view.script("window.scrollY") as Number).toDouble() > 1000.0)
        assertEquals("Paragraph 200", view.script("document.getElementById('paragraph-200').textContent"))
    }

    @Test
    fun `a preview that navigated on its own stops until shown again`(robot: FxRobot) {
        val (_, view) = show(robot, "# Mine\n")
        val host = RecordingHost()
        view.host = host
        val other = dir.resolve("other.html").apply { writeText("<h1>Other</h1>") }

        // No click or key press before: not the user's navigation.
        robot.interact { view.webView.engine.load(other.toUri().toString()) }
        WaitForAsyncUtils.waitForFxEvents()
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)

        assertTrue(robot.lookup(".preview-notice").query<Node>().isVisible)
        assertTrue("other.html" in view.notice.text)
        assertEquals(emptyList(), host.files)

        robot.interact { robot.lookup(".preview-notice .button").queryButton().fire() }
        awaitLoaded(view)
        assertTrue(!robot.lookup(".preview-notice").query<Node>().isVisible)
        assertEquals("Mine", view.script("document.querySelector('h1').textContent"))
        assertEquals("function", view.script("typeof sluideBridge.open"))
    }

    @Test
    fun `a preview that cannot be loaded says so, and tries again when asked`(robot: FxRobot) {
        var fail = true
        val preview =
            object : Preview {
                override fun load(
                    engine: WebEngine,
                    file: Path,
                    text: String,
                    dark: Boolean,
                    root: Path,
                ) = if (fail) throw IOException("no server") else engine.loadContent("<h1>Loaded</h1>")

                override val hasOwnScript get() = false
            }
        val file = dir.resolve("a.txt").apply { writeText("a") }
        val view =
            WaitForAsyncUtils
                .asyncFx<PreviewFileView> {
                    PreviewFileView(file, MarkdownFileViewer().highlighter, preview)
                }.get()
        robot.interact { container.children.setAll(view.node) }

        assertTrue(robot.lookup(".preview-notice").query<Node>().isVisible)
        assertEquals("The preview could not be shown: no server", view.notice.text)

        fail = false
        robot.interact { robot.lookup(".preview-notice .button").queryButton().fire() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            view.script("document.querySelector('h1') && document.querySelector('h1').textContent") == "Loaded"
        }
        assertTrue(!robot.lookup(".preview-notice").query<Node>().isVisible)
    }

    @Test
    fun `a disposed preview stops its page and stays blank`(robot: FxRobot) {
        val file = dir.resolve("index.html").apply { writeText("<h1>Served</h1>") }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { HtmlFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            view.script("document.querySelector('h1') && document.querySelector('h1').textContent") == "Served"
        }

        robot.interact { view.dispose() }
        // A page the view did not start would make it render again; give it the chance.
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()

        fun assertBlank() {
            val location = WaitForAsyncUtils.asyncFx<String?> { view.webView.engine.location }.get()
            assertTrue(isPageFromString(location), "blank, not $location")
            assertEquals(null, view.script("document.querySelector('h1')"))
        }
        assertBlank()
        assertTrue(!robot.lookup(".preview-notice").query<Node>().isVisible)

        // Neither a change of the file nor asking to show the page again brings it back.
        file.writeText("<h1>Changed</h1>")
        robot.interact { view.reload() }
        robot.interact { robot.lookup(".preview-notice .button").queryButton().fire() }
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()
        assertBlank()
    }

    @Test
    fun `the divider keeps its place when switching modes`(robot: FxRobot) {
        val (_, view) = show(robot, "# Title\n")
        val split = robot.lookup(".split-pane").queryAs(SplitPane::class.java)

        robot.interact { split.setDividerPositions(0.3) }
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        robot.interact { view.mode.set(PreviewMode.SPLIT) }

        assertEquals(0.3, split.dividerPositions[0], 0.01)
    }

    @Test
    fun `a Markdown file in Split gives the focus to its source, in Preview to the preview`(robot: FxRobot) {
        val (_, view) = show(robot, "# Title\n")
        WaitForAsyncUtils.waitForFxEvents()
        assertNotSame(
            view.webView,
            WaitForAsyncUtils.asyncFx<Any?> { container.scene.focusOwner }.get(),
            "not on its own",
        )

        robot.interact { view.focus() }
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(view.sourceArea, WaitForAsyncUtils.asyncFx<Any?> { container.scene.focusOwner }.get(), "Split")

        robot.interact {
            view.mode.set(PreviewMode.PREVIEW)
            view.focus()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(view.webView, WaitForAsyncUtils.asyncFx<Any?> { container.scene.focusOwner }.get(), "Preview")
    }

    /** A preview that keeps what [attach] gave it, like one that computes its page in the background. */
    private class PerViewPreview : Preview {
        val texts = mutableListOf<String>()
        var rerender: (() -> Unit)? = null
        var host: (() -> FileViewHost?)? = null
        var detached = 0
        var attachedBeforeLoad = true

        override fun load(
            engine: WebEngine,
            file: Path,
            text: String,
            dark: Boolean,
            root: Path,
        ) {
            attachedBeforeLoad = attachedBeforeLoad && rerender != null && host != null
            texts += text
            engine.loadContent("<h1>$text ${texts.size}</h1>")
        }

        override val hasOwnScript get() = false

        override fun attach(
            rerender: () -> Unit,
            host: () -> FileViewHost?,
        ) {
            this.rerender = rerender
            this.host = host
        }

        override fun detach() {
            detached++
        }
    }

    private fun viewOf(preview: Preview): PreviewFileView {
        val file = dir.resolve("a.txt").apply { writeText("a") }
        return WaitForAsyncUtils
            .asyncFx<PreviewFileView> { PreviewFileView(file, MarkdownFileViewer().highlighter, preview) }
            .get()
    }

    private fun PreviewFileView.heading() =
        script("document.querySelector('h1') && document.querySelector('h1').textContent")

    private fun PreviewFileView.noticeShown() = WaitForAsyncUtils.asyncFx<Boolean> { notice.parent.isVisible }.get()

    @Test
    fun `a preview can make its view render again, until the view is disposed`(robot: FxRobot) {
        val preview = PerViewPreview()
        val view = viewOf(preview)
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view)
        assertEquals(listOf("a"), preview.texts)
        assertTrue(preview.attachedBeforeLoad, "attach runs before the first load")

        robot.interact { preview.rerender?.invoke() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.heading() == "a 2" }
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        assertEquals(listOf("a", "a"), preview.texts)
        assertFalse(view.noticeShown(), view.notice.text)

        robot.interact { view.dispose() }
        assertEquals(1, preview.detached)
        robot.interact { preview.rerender?.invoke() }
        robot.interact { view.dispose() }
        assertEquals(listOf("a", "a"), preview.texts)
        assertEquals(1, preview.detached)
    }

    @Test
    fun `a preview rerendering while its page still loads is not taken for a page reloading itself`(robot: FxRobot) {
        val preview = PerViewPreview()
        val view = viewOf(preview)
        robot.interact { container.children.setAll(view.node) }
        // Twice in one pulse, the first load still running.
        robot.interact {
            preview.rerender?.invoke()
            preview.rerender?.invoke()
        }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.heading() == "a ${preview.texts.size}" }
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        assertEquals("a ${preview.texts.size}", view.heading())
        assertFalse(view.noticeShown(), view.notice.text)
    }

    @Test
    fun `a preview reaches the host of its view`(robot: FxRobot) {
        val preview = PerViewPreview()
        val view = viewOf(preview)
        val host = RecordingHost()
        assertEquals(null, preview.host?.invoke())
        robot.interact { view.host = host }
        assertSame(host, preview.host?.invoke())
        robot.interact { view.dispose() }
    }

    @Test
    fun `a viewer opens each view with the preview it makes for it`(robot: FxRobot) {
        val previews = mutableListOf<PerViewPreview>()
        val viewer =
            object : PreviewFileViewer(setOf("txt"), MarkdownFileViewer().highlighter, PerViewPreview()) {
                override fun newPreview(): Preview = PerViewPreview().also { previews += it }
            }
        val file = dir.resolve("a.txt").apply { writeText("a") }
        val views = WaitForAsyncUtils.asyncFx<List<Any>> { listOf(viewer.open(file), viewer.open(file)) }.get()
        assertEquals(2, previews.size)
        assertNotSame(previews[0], previews[1])
        robot.interact { views.forEach { (it as PreviewFileView).dispose() } }
        assertEquals(listOf(1, 1), previews.map { it.detached })
    }
}
