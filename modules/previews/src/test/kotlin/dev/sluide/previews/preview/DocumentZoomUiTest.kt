package dev.sluide.previews.preview

import dev.sluide.fileviewers.FileView
import dev.sluide.previews.html.HtmlFileViewer
import dev.sluide.previews.json.JsonFileViewer
import dev.sluide.previews.keyPress
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.scrollEvent
import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Zoom of the previews that zoom as documents with `WebView.zoom` (ADR 0083), over a JSON, a Markdown and an HTML
 * preview (scripts off): toolbar, keys, wheel, range, and what keeps the zoom. Needs a display, see the `uiTest`
 * Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DocumentZoomUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(container, 1000.0, 600.0)
        stage.show()
    }

    /** The stage is shared with the other test classes: sizing it to the scene again lets it follow their scenes. */
    @AfterEach
    fun sizeStageToScene() {
        fx { stage.sizeToScene() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    /** A kind of document preview: its file, and a text of it that shows [replaced] instead of [INITIAL]. */
    private class Kind(
        val name: String,
        val content: String,
        val replaced: String,
        val open: (Path) -> FileView,
    )

    private val kinds =
        listOf(
            Kind("data.json", """{"$INITIAL": 1}""", """{"$REPLACED": 1}""") { JsonFileViewer().open(it) },
            Kind("README.md", "# $INITIAL\n\nText\n", "# $REPLACED\n\nText\n") { MarkdownFileViewer().open(it) },
            Kind(
                "index.html",
                "<html><body><h1>$INITIAL</h1></body></html>",
                "<html><body><h1>$REPLACED</h1></body></html>",
            ) { HtmlFileViewer().open(it) },
        )

    private fun shows(
        view: PreviewFileView,
        text: String,
    ) = fx {
        val engine = view.webView.engine
        engine.loadWorker.state == Worker.State.SUCCEEDED &&
            engine.document
                ?.documentElement
                ?.textContent
                .orEmpty()
                .contains(text)
    }

    private fun awaitShows(
        view: PreviewFileView,
        text: String,
    ) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { shows(view, text) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun show(
        robot: FxRobot,
        kind: Kind,
        mode: PreviewMode = PreviewMode.SPLIT,
    ): PreviewFileView {
        val file = dir.resolve(kind.name).apply { writeText(kind.content) }
        val view = fx { kind.open(file) as PreviewFileView }
        robot.interact {
            container.children.setAll(view.node)
            view.mode.set(mode)
        }
        awaitShows(view, INITIAL)
        return view
    }

    /** Runs [check] for a JSON, a Markdown and an HTML preview, each shown in [mode]. */
    private fun forEachKind(
        robot: FxRobot,
        mode: PreviewMode = PreviewMode.SPLIT,
        check: (Kind, PreviewFileView) -> Unit,
    ) {
        for (kind in kinds) {
            try {
                check(kind, show(robot, kind, mode))
            } catch (e: Throwable) {
                throw AssertionError("${kind.name}: ${e.message}", e)
            }
        }
    }

    /** Fires [event] at [target] and returns whether a filter consumed it (it did not reach the container). */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Event> fire(
        target: Node,
        event: T,
    ): Boolean {
        var reached = false
        val handler = EventHandler<T> { reached = true }
        val type = event.eventType as EventType<T>
        fx {
            container.addEventHandler(type, handler)
            try {
                Event.fireEvent(target, event)
            } finally {
                container.removeEventHandler(type, handler)
            }
        }
        return !reached
    }

    private fun PreviewFileView.key(
        code: KeyCode,
        shortcut: Boolean = true,
    ) = fire(webView, keyPress(code, shortcut = shortcut))

    private fun PreviewFileView.scale() = fx { zoom.scale }

    private fun PreviewFileView.label() = fx { zoom.level.text }

    private fun PreviewFileView.webZoom() = fx { webView.zoom }

    private fun PreviewFileView.press(
        robot: FxRobot,
        button: Button,
    ) = robot.interact { button.fire() }

    /** Asserts that scale, label and `WebView.zoom` all say [scale]. */
    private fun assertZoom(
        view: PreviewFileView,
        scale: Double,
        label: String,
    ) {
        assertEquals(label, view.label())
        assertEquals(scale, view.scale(), 1e-9)
        assertEquals(scale, view.webZoom(), 1e-9)
    }

    private fun PreviewFileView.toolbar() = fx { node.lookup(".preview-toolbar") as HBox }

    @Test
    fun `the controls show in Split and Preview, not in Source, as three buttons before the mode toggles`(
        robot: FxRobot,
    ) {
        forEachKind(robot) { _, view ->
            for (mode in listOf(PreviewMode.SPLIT, PreviewMode.PREVIEW)) {
                robot.interact { view.mode.set(mode) }
                assertTrue(fx { view.zoom.node.isVisible }, "visible in $mode")
                assertTrue(fx { view.zoom.node.isManaged }, "managed in $mode")
            }
            robot.interact { view.mode.set(PreviewMode.SOURCE) }
            assertFalse(fx { view.zoom.node.isVisible }, "hidden in Source")
            assertFalse(fx { view.zoom.node.isManaged }, "unmanaged in Source")
            robot.interact { view.mode.set(PreviewMode.SPLIT) }
            assertTrue(fx { view.zoom.node.isVisible })

            // Documents have no Fit and open at 100 percent.
            assertNull(view.zoom.fitButton)
            assertFalse(fx { view.zoom.fit })
            assertEquals(3, fx { view.node.lookupAll(".zoom-button").size }, "zoom out, zoom in and 100%")
            assertEquals(
                emptyList(),
                fx {
                    view
                        .toolbar()
                        .children
                        .filterIsInstance<ToggleButton>()
                        .filter { it.text == "Fit" }
                },
            )
            assertZoom(view, 1.0, "100%")

            // After the action buttons and directly before the mode toggles.
            val children = fx { view.toolbar().children.toList() }
            val modes = PreviewMode.entries.size
            val index = children.indexOf(view.zoom.node)
            assertEquals(children.size - modes - 1, index, "directly before the mode toggles")
            assertEquals(
                List(modes) { true },
                children.takeLast(modes).map { it is ToggleButton && it.styleClass.contains("preview-mode") },
            )
            for (action in view.actionButtons) assertTrue(children.indexOf(action) in 0 until index)
        }
    }

    @Test
    fun `a key, a button and the wheel zoom each preview, whose page sees the scale`(robot: FxRobot) {
        // The input handling itself is tested in ZoomControlsUiTest; this checks the wiring of each preview.
        forEachKind(robot) { _, view ->
            assertTrue(view.key(KeyCode.EQUALS), "consumed")
            assertZoom(view, 1.25, "125%")
            view.press(robot, view.zoom.actualSizeButton)
            assertZoom(view, 1.0, "100%")
            assertTrue(fire(view.webView, scrollEvent(40.0, shortcut = true)), "consumed")
            assertZoom(view, 1.1, "110%")
        }
    }

    /** Shows [kind]'s preview without waiting for its page: the toolbar does not depend on it. */
    private fun showAtOnce(
        robot: FxRobot,
        kind: Kind,
    ): PreviewFileView {
        val file = dir.resolve(kind.name).apply { writeText(kind.content) }
        val view = fx { kind.open(file) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    @Test
    fun `the left of the toolbar keeps the buttons of the preview`(robot: FxRobot) {
        val json = showAtOnce(robot, kinds[0])
        val jsonChildren = fx { json.toolbar().children.toList() }
        assertSame(json.folds!!.node, jsonChildren[0], "Collapse and Expand first")
        assertSame(json.copyFormatted!!.node, jsonChildren[1], "then Copy Formatted")

        val markdown = showAtOnce(robot, kinds[1])
        assertSame(markdown.toc!!.button, fx { markdown.toolbar().children[0] }, "the table of contents first")

        val html = showAtOnce(robot, kinds[2])
        val first = fx { html.toolbar().children[0] }
        assertTrue(first is ToggleButton && first.text == "Scripts", "Scripts first")
        assertFalse(html.scripts.get(), "scripts are off by default")
    }

    @Test
    fun `the zoom survives saving the file and rendering the page again`(robot: FxRobot) {
        forEachKind(robot) { kind, view ->
            view.key(KeyCode.EQUALS)
            view.key(KeyCode.EQUALS)
            assertZoom(view, 1.5, "150%")

            robot.interact { view.sourceArea.replaceText(0, view.sourceArea.length, kind.replaced) }
            robot.interact { view.save() }
            awaitShows(view, REPLACED)

            assertZoom(view, 1.5, "150%")
        }
    }

    @Test
    fun `the zoom survives a change of the theme`(robot: FxRobot) {
        forEachKind(robot) { _, view ->
            view.key(KeyCode.EQUALS)
            try {
                robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
                WaitForAsyncUtils.waitForFxEvents()
                awaitShows(view, INITIAL)
                assertZoom(view, 1.25, "125%")
            } finally {
                robot.interact { container.scene.preferences.colorScheme = ColorScheme.LIGHT }
                WaitForAsyncUtils.waitForFxEvents()
            }
        }
    }

    @Test
    fun `a Markdown preview lines up with the top line of the source again after a zoom`(robot: FxRobot) {
        val text = (1..400).joinToString("\n\n", postfix = "\n") { "Paragraph $it with a few words in it." }
        assertAlignsAfterZoom(robot, "long.md", text, "Paragraph 400") { MarkdownFileViewer().open(it) }
    }

    @Test
    fun `a JSON preview lines up with the top line of the source again after a zoom`(robot: FxRobot) {
        val text = (1..400).joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { "  \"key$it\": $it" }
        assertAlignsAfterZoom(robot, "long.json", text, "key400") { JsonFileViewer().open(it) }
    }

    @Test
    fun `the zoom of an HTML page survives turning its scripts on and off`(robot: FxRobot) {
        val view = show(robot, kinds[2])
        view.confirmScripts = { true }
        view.key(KeyCode.EQUALS)
        assertZoom(view, 1.25, "125%")

        robot.interact { view.scripts.set(true) }
        awaitShows(view, INITIAL)
        assertZoom(view, 1.25, "125%")
        view.key(KeyCode.EQUALS)
        assertZoom(view, 1.5, "150%")

        robot.interact { view.scripts.set(false) }
        awaitShows(view, INITIAL)
        assertZoom(view, 1.5, "150%")
    }

    private fun assertAlignsAfterZoom(
        robot: FxRobot,
        name: String,
        text: String,
        last: String,
        open: (Path) -> FileView,
    ) {
        val file = dir.resolve(name).apply { writeText(text) }
        val view = fx { open(file) as PreviewFileView }
        robot.interact {
            container.children.setAll(view.node)
            view.mode.set(PreviewMode.SPLIT)
        }
        awaitShows(view, last)

        fun previewTop() = fx { view.webView.engine.executeScript("sluide.topLine()") as Number }.toInt()

        fun sourceTop() = fx { view.source.topLine }

        fun awaitAligned() = WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { abs(previewTop() - sourceTop()) <= 2 }

        robot.interact { view.source.showLineAtTop(300) }
        awaitAligned()
        assertTrue(sourceTop() in 290..310, "source at ${sourceTop()}")

        repeat(3) { view.key(KeyCode.EQUALS) }
        assertEquals(2.0, view.scale(), 1e-9)
        awaitAligned()

        view.key(KeyCode.DIGIT0)
        awaitAligned()
        repeat(2) { view.key(KeyCode.MINUS) }
        awaitAligned()
    }

    private companion object {
        const val INITIAL = "INITIALMARK"
        const val REPLACED = "REPLACEDMARK"
    }
}
