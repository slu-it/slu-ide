package dev.sluide.fileviewers

import javafx.concurrent.Worker
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent
import javafx.scene.layout.StackPane
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
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Zoom (ADR 0033) beyond the basics of the image and preview tests: keys, wheel and pinch fired at the web view,
 * clamping, fit and resizing, clicks, scrolling, and the previews that do not zoom. Needs a display, see the `uiTest`
 * Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ZoomUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(container, 600.0, 400.0)
        stage.show()
    }

    /**
     * The stage is shared with the other test classes: once a test sized it, it would keep that size for their scenes.
     * Sizing it to the scene again lets it follow them.
     */
    @AfterEach
    fun sizeStageToScene() {
        fx { stage.sizeToScene() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun WebView.script(code: String): Any? = fx { engine.executeScript(code) }

    private fun WebView.number(code: String) = (script(code) as Number).toDouble()

    private fun WebView.imageWidth() = number("document.querySelector('img').getBoundingClientRect().width")

    private fun awaitLoaded(webView: WebView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED } &&
                webView.script("document.querySelector('img') && document.querySelector('img').complete") == true
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun png(
        name: String,
        width: Int,
        height: Int,
    ): Path =
        dir.resolve(name).also {
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it.toFile())
        }

    private fun showImage(
        robot: FxRobot,
        width: Int,
        height: Int,
    ): ImageFileView {
        val view = fx { ImageFileViewer().open(png("image.png", width, height)) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)
        return view
    }

    /**
     * Fires [event] at [target] and returns whether a filter consumed it: an event that nothing consumed also reaches
     * a handler on the container, further up.
     */
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

    private fun key(
        view: ImageFileView,
        code: KeyCode,
        shortcut: Boolean = true,
    ) = fire(view.webView, keyPress(code, shortcut = shortcut))

    private val shortcutIsMeta = System.getProperty("os.name").startsWith("Mac")

    private fun scroll(
        deltaY: Double,
        shortcut: Boolean,
    ) = ScrollEvent(
        ScrollEvent.SCROLL,
        100.0,
        100.0,
        100.0,
        100.0,
        false,
        shortcut && !shortcutIsMeta,
        false,
        shortcut && shortcutIsMeta,
        false,
        false,
        0.0,
        deltaY,
        0.0,
        deltaY,
        ScrollEvent.HorizontalTextScrollUnits.NONE,
        0.0,
        ScrollEvent.VerticalTextScrollUnits.NONE,
        0.0,
        0,
        null,
    )

    private fun pinch(factor: Double) =
        ZoomEvent(
            ZoomEvent.ZOOM,
            100.0,
            100.0,
            100.0,
            100.0,
            false,
            false,
            false,
            false,
            true,
            false,
            factor,
            factor,
            null,
        )

    private fun ImageFileView.state() = fx { zoom.scale to zoom.fit }

    private fun ImageFileView.scale() = fx { zoom.scale }

    @Test
    fun `Shortcut with plus, equals and numpad plus zooms in and consumes the key`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        for (code in listOf(KeyCode.EQUALS, KeyCode.PLUS, KeyCode.ADD)) {
            assertTrue(key(view, KeyCode.DIGIT0), "100% consumed")
            assertTrue(key(view, code), "$code consumed")
            assertEquals(1.25, view.scale(), "$code")
            assertEquals(500.0, view.webView.imageWidth(), "$code")
            assertFalse(fx { view.zoom.fit }, "$code")
        }
    }

    @Test
    fun `Shortcut with minus and numpad minus zooms out and consumes the key`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        for (code in listOf(KeyCode.MINUS, KeyCode.SUBTRACT)) {
            assertTrue(key(view, KeyCode.DIGIT0))
            assertTrue(key(view, code), "$code consumed")
            assertEquals(0.75, view.scale(), "$code")
            assertEquals(300.0, view.webView.imageWidth(), "$code")
        }
    }

    @Test
    fun `Shortcut with 0 and numpad 0 shows 100 percent, with 9 and numpad 9 fits`(robot: FxRobot) {
        val view = showImage(robot, 2000, 100)
        val fitted = view.scale()
        assertTrue(fitted < 0.5, "fitted: $fitted")
        for (code in listOf(KeyCode.DIGIT0, KeyCode.NUMPAD0)) {
            assertTrue(key(view, KeyCode.DIGIT9))
            assertTrue(key(view, KeyCode.EQUALS))
            assertTrue(key(view, code), "$code consumed")
            assertEquals(1.0, view.scale(), "$code")
            assertEquals(2000.0, view.webView.imageWidth(), "$code")
            assertFalse(fx { view.zoom.fit })
        }
        for (code in listOf(KeyCode.DIGIT9, KeyCode.NUMPAD9)) {
            assertTrue(key(view, KeyCode.DIGIT0))
            assertTrue(key(view, code), "$code consumed")
            assertEquals(fitted, view.scale(), 1e-9, "$code")
            assertTrue(view.webView.imageWidth() < 600, "$code")
            assertTrue(fx { view.zoom.fit && view.zoom.fitButton.isSelected }, "$code")
        }
    }

    @Test
    fun `the same keys without Shortcut do nothing`(robot: FxRobot) {
        val view = showImage(robot, 2000, 100)
        val before = view.state()
        val width = view.webView.imageWidth()
        val codes =
            listOf(
                KeyCode.EQUALS,
                KeyCode.PLUS,
                KeyCode.ADD,
                KeyCode.MINUS,
                KeyCode.SUBTRACT,
                KeyCode.DIGIT0,
                KeyCode.NUMPAD0,
                KeyCode.DIGIT9,
                KeyCode.NUMPAD9,
            )
        for (code in codes) {
            key(view, code, shortcut = false)
            assertEquals(before, view.state(), "$code")
            assertEquals(width, view.webView.imageWidth(), "$code")
        }
        // Not fitted any more: the keys without Shortcut leave a fixed scale alone, too.
        robot.interact { view.zoom.actualSizeButton.fire() }
        for (code in codes) {
            key(view, code, shortcut = false)
            assertEquals(1.0 to false, view.state(), "$code")
        }
    }

    @Test
    fun `zoom keys in the source editor of a Mermaid file do not zoom the preview`(robot: FxRobot) {
        val file = dir.resolve("flow.mmd").apply { writeText("flowchart TD\n  A --> B\n") }
        val view = fx { MermaidFileViewer().open(file) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { view.webView.engine.loadWorker.state } ==
                Worker.State.SUCCEEDED
        }
        view.webView.script("window.drawn = false; sluide.renderDiagrams().then(function () { window.drawn = true; })")
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.webView.script("window.drawn === true") == true }
        assertEquals(PreviewMode.SPLIT, view.mode.get())
        val zoom = checkNotNull(view.zoom)
        val before = fx { zoom.scale to zoom.fit }
        assertTrue(before.second)

        val codes =
            listOf(
                KeyCode.EQUALS,
                KeyCode.PLUS,
                KeyCode.ADD,
                KeyCode.MINUS,
                KeyCode.SUBTRACT,
                KeyCode.DIGIT0,
                KeyCode.NUMPAD0,
                KeyCode.DIGIT9,
                KeyCode.NUMPAD9,
            )
        for (code in codes) {
            fire(view.source.area, keyPress(code))
            assertEquals(before, fx { zoom.scale to zoom.fit }, "$code in the source")
        }
        assertEquals("100%", fx { zoom.level.text })

        // The same key on the preview does zoom.
        assertTrue(fire(view.webView, keyPress(KeyCode.EQUALS)))
        assertEquals(1.25, fx { zoom.scale })
        assertFalse(fx { zoom.fit })
    }

    @Test
    fun `Shortcut with the wheel zooms in and out around the pointer and consumes the event`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        assertTrue(fire(view.webView, scroll(40.0, shortcut = true)), "consumed")
        assertEquals(1.1, view.scale(), 1e-6)
        assertEquals(440.0, view.webView.imageWidth(), 0.5)
        assertFalse(fx { view.zoom.fit })

        assertTrue(fire(view.webView, scroll(-40.0, shortcut = true)), "consumed")
        assertTrue(fire(view.webView, scroll(-40.0, shortcut = true)), "consumed")
        assertEquals(1 / 1.1, view.scale(), 1e-6)
        assertTrue(view.scale() < 1.0)
        assertEquals("91%", fx { view.zoom.level.text })
    }

    @Test
    fun `a plain wheel scroll does not zoom`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        val before = view.state()
        fire(view.webView, scroll(40.0, shortcut = false))
        fire(view.webView, scroll(-40.0, shortcut = false))
        assertEquals(before, view.state())
        assertEquals(400.0, view.webView.imageWidth())
        assertTrue(fx { view.zoom.fit })
    }

    @Test
    fun `pinching zooms by the zoom factor of the event`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        assertTrue(fire(view.webView, pinch(1.5)), "consumed")
        assertEquals(1.5, view.scale(), 1e-6)
        assertEquals(600.0, view.webView.imageWidth(), 0.5)
        assertFalse(fx { view.zoom.fit })

        assertTrue(fire(view.webView, pinch(0.5)))
        assertEquals(0.75, view.scale(), 1e-6)
        assertEquals(300.0, view.webView.imageWidth(), 0.5)
    }

    /** The image pixel under [point] (web view coordinates, the page's viewport). */
    private fun ImageFileView.pixelUnder(point: Point2D): Point2D {
        val box = "document.querySelector('img').getBoundingClientRect()"
        val left = webView.number("$box.left")
        val top = webView.number("$box.top")
        val scale = webView.number("$box.width") / 2000
        return Point2D((point.x - left) / scale, (point.y - top) / scale)
    }

    @Test
    fun `the wheel and pinching keep the point under the pointer in place`(robot: FxRobot) {
        val view = showImage(robot, 2000, 1500)
        robot.interact { view.zoom.actualSizeButton.fire() }
        view.webView.script("window.scrollTo(300, 200)")
        // The events are at (100, 100) in the scene, which is below the toolbar in the web view.
        val pointer = fx { view.webView.sceneToLocal(100.0, 100.0) }
        val pixel = view.pixelUnder(pointer)
        assertTrue(pixel.x > 300 && pixel.y > 200, "scrolled: $pixel")

        for (event in listOf(scroll(40.0, shortcut = true), pinch(1.5), scroll(-80.0, shortcut = true), pinch(0.8))) {
            val scale = view.scale()
            assertTrue(fire(view.webView, event))
            assertTrue(abs(view.scale() - scale) > 0.01, "zoomed from $scale")
            val now = view.pixelUnder(pointer)
            assertEquals(pixel.x, now.x, 2.0, "x at ${view.scale()}")
            assertEquals(pixel.y, now.y, 2.0, "y at ${view.scale()}")
        }
    }

    @Test
    fun `the mode toggles leave the focus in the preview, which the view focuses when it is shown alone`(
        robot: FxRobot,
    ) {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60"></svg>"""
        val view = fx { SvgFileViewer().open(dir.resolve("logo.svg").apply { writeText(svg) }) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)
        val zoom = checkNotNull(view.zoom)

        robot.interact { view.focusText() }
        assertEquals(view.webView, fx { container.scene.focusOwner }, "Preview: the preview")
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        robot.interact { view.focusText() }
        assertEquals(view.source.area, fx { container.scene.focusOwner }, "Split: the source")

        robot.interact { view.webView.requestFocus() }
        val preview = fx { view.node.lookupAll(".preview-mode").first { (it as ToggleButton).text == "Preview" } }
        click(preview)
        assertEquals(PreviewMode.PREVIEW, view.mode.get())
        assertEquals(view.webView, fx { container.scene.focusOwner }, "the toggle takes no focus")
        robot.press(fx { container.scene.focusOwner }, KeyCode.EQUALS, shortcut = true)
        assertEquals(1.25, fx { zoom.scale })
    }

    /** A primary click on [node] as the mouse makes it: press, release, click. */
    private fun click(node: Node) {
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            val event =
                MouseEvent(
                    type,
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
                    type == MouseEvent.MOUSE_PRESSED,
                    false,
                    false,
                    false,
                    false,
                    true,
                    null,
                )
            fx { Event.fireEvent(node, event) }
        }
    }

    @Test
    fun `zooming in stops at 1600 percent and zooming out at 10 percent`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        repeat(25) { robot.interact { view.zoom.zoomInButton.fire() } }
        assertEquals("1600%", fx { view.zoom.level.text })
        assertEquals(16.0, view.scale())
        assertEquals(6400.0, view.webView.imageWidth(), 0.5)

        repeat(30) { robot.interact { view.zoom.zoomOutButton.fire() } }
        assertEquals("10%", fx { view.zoom.level.text })
        assertEquals(0.1, view.scale())
        assertEquals(40.0, view.webView.imageWidth(), 0.5)

        // The wheel and pinching stop there as well.
        repeat(5) { fire(view.webView, pinch(0.5)) }
        assertEquals("10%", fx { view.zoom.level.text })
        assertEquals(40.0, view.webView.imageWidth(), 0.5)
        repeat(10) { fire(view.webView, pinch(4.0)) }
        assertEquals("1600%", fx { view.zoom.level.text })
        assertEquals(6400.0, view.webView.imageWidth(), 0.5)
    }

    @Test
    fun `Fit never enlarges a small image`(robot: FxRobot) {
        val view = showImage(robot, 100, 50)
        assertEquals(100.0, view.webView.imageWidth())
        robot.interact { view.zoom.zoomInButton.fire() }
        assertEquals(125.0, view.webView.imageWidth())
        robot.interact { view.zoom.fitButton.fire() }
        assertEquals(100.0, view.webView.imageWidth())
        assertEquals(1.0, view.scale())
        assertEquals("100%", fx { view.zoom.level.text })
        assertTrue(fx { view.zoom.fit && view.zoom.fitButton.isSelected })

        robot.interact { stage.width = 1000.0 }
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(100.0, view.webView.imageWidth(), "a bigger view does not enlarge it")
        assertEquals(1.0, view.scale())
    }

    @Test
    fun `a fitted large image follows the size of the view, a zoomed one does not`(robot: FxRobot) {
        val view = showImage(robot, 3000, 2000)
        val small = view.scale()
        assertTrue(small < 0.2, "fitted: $small")
        assertTrue(fx { view.zoom.fit })

        robot.interact { stage.width = 1000.0 }
        robot.interact { stage.height = 800.0 }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.scale() > small * 1.2 }
        val bigger = view.scale()
        assertTrue(bigger < 1.0, "still below 100%: $bigger")
        assertTrue(fx { view.zoom.fit && view.zoom.fitButton.isSelected })
        assertEquals(ZoomSteps.label(bigger), fx { view.zoom.level.text })
        assertEquals(3000 * bigger, view.webView.imageWidth(), 1.0)

        robot.interact { stage.width = 500.0 }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.scale() < bigger * 0.9 }
        assertTrue(fx { view.zoom.fit })

        // A manual zoom stays where it is, however the view is resized.
        robot.interact { view.zoom.zoomInButton.fire() }
        val manual = view.scale()
        assertFalse(fx { view.zoom.fit })
        val width = view.webView.imageWidth()
        robot.interact { stage.width = 900.0 }
        robot.interact { stage.height = 500.0 }
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(manual, view.scale())
        assertEquals(width, view.webView.imageWidth(), 0.5)
        assertFalse(fx { view.zoom.fit })
    }

    @Test
    fun `clicking Fit while fitted keeps it selected and fitted`(robot: FxRobot) {
        val view = showImage(robot, 2000, 100)
        val fitted = view.webView.imageWidth()
        assertTrue(fitted < 600)
        robot.interact { view.zoom.fitButton.fire() }
        assertTrue(fx { view.zoom.fitButton.isSelected }, "still selected")
        assertTrue(fx { view.zoom.fit })
        assertEquals(fitted, view.webView.imageWidth(), 0.5)
        assertEquals(true, view.webView.script("document.body.classList.contains('fitted')"))
    }

    @Test
    fun `a click from a fixed scale other than 100 percent goes back to fit`(robot: FxRobot) {
        val view = showImage(robot, 2000, 100)
        val fitted = view.webView.imageWidth()
        robot.interact { view.zoom.actualSizeButton.fire() }
        robot.interact { view.zoom.zoomInButton.fire() }
        assertEquals("125%", fx { view.zoom.level.text })
        assertEquals(2500.0, view.webView.imageWidth(), 0.5)

        view.webView.script("document.body.click()")
        assertTrue(fx { view.zoom.fit && view.zoom.fitButton.isSelected })
        assertEquals(fitted, view.webView.imageWidth(), 0.5)

        robot.interact { view.zoom.zoomOutButton.fire() }
        assertFalse(fx { view.zoom.fit })
        view.webView.script("document.body.click()")
        assertTrue(fx { view.zoom.fit })
        assertEquals(fitted, view.webView.imageWidth(), 0.5)
    }

    @Test
    fun `a zoomed image scrolls to every edge`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        robot.interact { view.zoom.actualSizeButton.fire() }
        repeat(5) { robot.interact { view.zoom.zoomInButton.fire() } }
        assertEquals("400%", fx { view.zoom.level.text })
        assertEquals(1600.0, view.webView.imageWidth(), 0.5)
        val web = view.webView
        val maxX = web.number("document.documentElement.scrollWidth - window.innerWidth")
        val maxY = web.number("document.documentElement.scrollHeight - window.innerHeight")
        assertTrue(maxX > 0 && maxY > 0, "the page is larger than the view: $maxX x $maxY")

        web.script("window.scrollTo(0, 0)")
        assertEquals(0.0, web.number("window.scrollX"))
        assertEquals(0.0, web.number("window.scrollY"))
        assertTrue(web.number("document.querySelector('img').getBoundingClientRect().left") >= 0.0, "left")
        assertTrue(web.number("document.querySelector('img').getBoundingClientRect().top") >= 0.0, "top")

        web.script("window.scrollTo(document.documentElement.scrollWidth, document.documentElement.scrollHeight)")
        assertEquals(maxX, web.number("window.scrollX"), 1.0)
        assertEquals(maxY, web.number("window.scrollY"), 1.0)
        assertTrue(
            web.number("document.querySelector('img').getBoundingClientRect().left + window.scrollX") >= 0.0,
            "left, in the page",
        )
        assertTrue(
            web.number("document.querySelector('img').getBoundingClientRect().top + window.scrollY") >= 0.0,
            "top, in the page",
        )
        assertTrue(
            web.number("document.querySelector('img').getBoundingClientRect().right") <=
                web.number("window.innerWidth") + 1,
            "the right edge shows at the end",
        )
        assertTrue(
            web.number("document.querySelector('img').getBoundingClientRect().bottom") <=
                web.number("window.innerHeight") + 1,
            "the bottom edge shows at the end",
        )
    }

    @Test
    fun `Markdown previews have no zoom controls`(robot: FxRobot) {
        val file = dir.resolve("README.md").apply { writeText("# Title\n\nText\n") }
        val view = fx { MarkdownFileViewer().open(file) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { view.webView.engine.loadWorker.state } ==
                Worker.State.SUCCEEDED
        }

        assertNull(view.zoom)
        assertTrue(fx { view.node.lookupAll(".zoom-button").isEmpty() })
        assertTrue(fx { view.node.lookupAll(".zoom-controls").isEmpty() })
        assertNotNull(fx { view.node.lookup(".preview-toolbar") }, "the toolbar is there, without zoom")
    }

    @Test
    fun `notebook previews have no zoom controls`(robot: FxRobot) {
        val file =
            dir.resolve("note.ipynb").apply {
                writeText("""{"cells": [], "metadata": {}, "nbformat": 4, "nbformat_minor": 5}""")
            }
        val view = fx { NotebookFileViewer().open(file) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { view.webView.engine.loadWorker.state } ==
                Worker.State.SUCCEEDED
        }

        assertNull(view.zoom)
        assertTrue(fx { view.node.lookupAll(".zoom-button").isEmpty() })
    }

    @Test
    fun `an image view put into a scene takes the focus, and the zoom keys work at once`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        WaitForAsyncUtils.waitForFxEvents()

        assertSame(view.webView, fx { container.scene.focusOwner })
        assertEquals(1.0, view.scale())
        robot.interact { Event.fireEvent(container.scene.focusOwner, keyPress(KeyCode.EQUALS)) }
        assertEquals(1.25, view.scale())
    }

    @Test
    fun `an image view takes the focus again when its tab is shown again`(robot: FxRobot) {
        val view = showImage(robot, 400, 200)
        val other = TextField()
        robot.interact { container.children.setAll(other) }
        robot.interact { other.requestFocus() }
        assertSame(other, fx { container.scene.focusOwner })

        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(view.webView, fx { container.scene.focusOwner })
    }

    @Test
    fun `an image view that is disposed before it gets its scene does not take the focus`(robot: FxRobot) {
        val file = png("late.png", 40, 40)
        val view = fx { ImageFileViewer().open(file) as ImageFileView }
        val other = TextField()
        robot.interact { container.children.setAll(other) }
        robot.interact { other.requestFocus() }
        robot.interact {
            container.children.add(view.node)
            view.dispose()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(other, fx { container.scene.focusOwner })
    }
}
