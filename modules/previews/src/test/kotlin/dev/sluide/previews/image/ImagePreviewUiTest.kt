package dev.sluide.previews.image

import dev.sluide.fileviewers.zoom.ZoomSteps
import dev.sluide.imagelimits.TestImages
import dev.sluide.previews.press
import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewMode
import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.fileSize
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Image and SVG previews with a real WebView. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ImagePreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 600.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun WebView.script(code: String): Any? = fx { engine.executeScript(code) }

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

    private fun WebView.imageWidth() =
        (script("document.querySelector('img').getBoundingClientRect().width") as Number).toDouble()

    @Test
    fun `shows an image with its pixel and file size, fitted until clicked`(robot: FxRobot) {
        val file = png("wide.png", 2000, 100)
        val view = fx { ImageFileViewer().open(file) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        assertEquals("2000 × 100 px · ${humanSize(file.fileSize())}", fx { view.status.text })
        val fitted = view.webView.imageWidth()
        assertTrue(fitted < 600, "fitted: $fitted")
        assertTrue(fx { view.zoom.fit && view.zoom.fitButton?.isSelected == true })
        assertEquals(ZoomSteps.label(fitted / 2000), fx { view.zoom.level.text })

        view.webView.script("document.body.click()")
        assertEquals(2000.0, view.webView.imageWidth())
        assertFalse(fx { view.zoom.fit || view.zoom.fitButton?.isSelected == true })
        assertEquals("100%", fx { view.zoom.level.text })

        view.webView.script("document.body.click()")
        assertEquals(fitted, view.webView.imageWidth())
        assertTrue(fx { view.zoom.fit })
    }

    private fun refusalShown(view: ImageFileView) = fx { (view.node as BorderPane).center !== view.webView }

    /** Waits for the blank page the view loads when it refuses an image: no page of ours was or is loaded. */
    private fun awaitBlank(webView: WebView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED && webView.engine.location == "about:blank" }
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `an image whose header claims too many pixels, or that cannot be read, shows a message and loads no page`(
        robot: FxRobot,
    ) {
        val bomb = dir.resolve("bomb.png").apply { writeBytes(TestImages.pngHeader(30_000, 30_000)) }
        val view = fx { ImageFileViewer().open(bomb) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }

        assertTrue(refusalShown(view))
        awaitBlank(view.webView)
        assertEquals(null, view.webView.script("document.querySelector('img')"))
        assertFalse(fx { view.isLoaded })
        // A change of the scheme must not script the blank page either.
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        assertFalse(fx { view.isLoaded })
        assertEquals(false, view.webView.script("document.body.classList.contains('dark')"))
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.LIGHT }
        assertEquals("30 000 × 30 000 px · ${humanSize(bomb.fileSize())}", fx { view.status.text })
        val message = fx { ((view.node as BorderPane).center as Label).text }
        assertEquals("30 000 × 30 000 px — too large to show (at most 40 megapixels)", message)
        assertTrue(fx { view.zoom.node.isDisable })
        // Muted and padded by preview-view.css (computed from the stylesheet, so a lost rule fails here).
        val label = fx { (view.node as BorderPane).center as Label }
        assertEquals(0.7, fx { label.opacity })
        assertEquals(24.0, fx { label.padding.top })

        val garbage = dir.resolve("garbage.png").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        val unreadable = fx { ImageFileViewer().open(garbage) as ImageFileView }
        robot.interact { container.children.setAll(unreadable.node) }
        assertEquals("The image could not be read.", fx { ((unreadable.node as BorderPane).center as Label).text })
        assertEquals(humanSize(garbage.fileSize()), fx { unreadable.status.text })
        awaitBlank(unreadable.webView)
        assertEquals(null, unreadable.webView.script("document.querySelector('img')"))
    }

    @Test
    fun `a reload to an image over the cap unloads the page, and a reload back shows the image again`(robot: FxRobot) {
        val file = png("icon.png", 10, 10)
        val view = fx { ImageFileViewer().open(file) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)
        assertFalse(refusalShown(view))

        file.writeBytes(TestImages.pngHeader(30_000, 30_000))
        robot.interact { view.reload() }
        assertTrue(refusalShown(view))
        awaitBlank(view.webView)
        assertEquals(null, view.webView.script("document.querySelector('img')"))
        assertTrue(fx { view.zoom.node.isDisable })
        assertTrue(fx { view.status.text.startsWith("30 000 × 30 000 px · ") })

        png("icon.png", 30, 20)
        robot.interact { view.reload() }
        awaitLoaded(view.webView)
        assertFalse(refusalShown(view))
        assertFalse(fx { view.zoom.node.isDisable })
        assertEquals(30, (view.webView.script("document.querySelector('img').naturalWidth") as Number).toInt())
        assertEquals("30 × 20 px · ${humanSize(file.fileSize())}", fx { view.status.text })
    }

    @Test
    fun `while the message shows the pane takes the focus, and a request waits for the view to be in the scene`(
        robot: FxRobot,
    ) {
        val other = TextField()
        robot.interact { container.children.setAll(other) }
        robot.interact { other.requestFocus() }
        val bomb = dir.resolve("bomb.png").apply { writeBytes(TestImages.pngHeader(30_000, 30_000)) }
        val view = fx { ImageFileViewer().open(bomb) as ImageFileView }

        // Out of the scene: nothing happens yet, also not when the image changes meanwhile.
        robot.interact { view.focus() }
        png("bomb.png", 10, 10)
        robot.interact { view.reload() }
        assertEquals(other, fx { container.scene.focusOwner })
        // Shown at last: the request runs and picks the web view, as the image shows now.
        robot.interact { container.children.setAll(other, view.node) }
        awaitLoaded(view.webView)
        assertEquals(view.webView, fx { container.scene.focusOwner })

        // A refused view in the scene takes the focus itself, with either call.
        robot.interact { other.requestFocus() }
        bomb.writeBytes(TestImages.pngHeader(30_000, 30_000))
        robot.interact { view.reload() }
        robot.interact { view.focus() }
        assertEquals(view.node, fx { container.scene.focusOwner })
        robot.interact { other.requestFocus() }
        robot.interact { view.focusText() }
        assertEquals(view.node, fx { container.scene.focusOwner })
    }

    @Test
    fun `the zoom controls and keys size the image, and the zoom is kept when it is shown again`(robot: FxRobot) {
        val file = png("photo.png", 400, 200)
        val view = fx { ImageFileViewer().open(file) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)
        assertEquals(400.0, view.webView.imageWidth(), "a small image fits at 100%")
        assertEquals("100%", fx { view.zoom.level.text })

        robot.interact { view.zoom.zoomInButton.fire() }
        assertEquals(500.0, view.webView.imageWidth())
        assertEquals("125%", fx { view.zoom.level.text })
        robot.interact { view.zoom.zoomOutButton.fire() }
        robot.interact { view.zoom.zoomOutButton.fire() }
        assertEquals(300.0, view.webView.imageWidth())
        robot.press(view.webView, KeyCode.EQUALS, shortcut = true)
        assertEquals(400.0, view.webView.imageWidth())
        robot.press(view.webView, KeyCode.EQUALS, shortcut = true)
        assertEquals("125%", fx { view.zoom.level.text })

        png("photo.png", 400, 300)
        robot.interact { view.reload() }
        awaitLoaded(view.webView)
        assertEquals(500.0, view.webView.imageWidth())
        assertFalse(fx { view.zoom.fit })

        robot.interact { checkNotNull(view.zoom.fitButton).fire() }
        assertEquals(400.0, view.webView.imageWidth())
        assertTrue(fx { view.zoom.fit && view.zoom.fitButton?.isSelected == true })

        robot.interact { view.zoom.actualSizeButton.fire() }
        assertEquals(400.0, view.webView.imageWidth())
        assertEquals("100%", fx { view.zoom.level.text })
        assertFalse(fx { view.zoom.fit })
    }

    @Test
    fun `huge content fits below 10 percent, and zooming out from there never enlarges it`(robot: FxRobot) {
        val view = fx { ImageFileViewer().open(png("banner.png", 8000, 100)) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        val fitted = view.webView.imageWidth()
        val scale = fx { view.zoom.scale }
        assertTrue(scale < 0.1 && fx { view.zoom.fit }, "fitted at $scale")
        assertEquals(8000 * scale, fitted, 0.5)
        val viewport = (view.webView.script("window.innerWidth") as Number).toDouble()
        assertTrue(fitted <= viewport - 32, "all of it shows: $fitted in $viewport")
        assertEquals(viewport, (view.webView.script("document.documentElement.scrollWidth") as Number).toDouble())

        robot.interact { view.zoom.zoomOutButton.fire() }
        assertEquals(fitted, view.webView.imageWidth(), 0.01)
        robot.press(view.webView, KeyCode.MINUS, shortcut = true)
        assertEquals(fitted, view.webView.imageWidth(), 0.01)
        view.webView.script("sluide.zoomBy(0.5, 10, 10)")
        assertEquals(fitted, view.webView.imageWidth(), 0.01)
        assertEquals(scale, fx { view.zoom.scale }, 1e-9)

        robot.interact { view.zoom.zoomInButton.fire() }
        assertEquals(800.0, view.webView.imageWidth(), 0.01)
        assertEquals("10%", fx { view.zoom.level.text })
    }

    @Test
    fun `the zoom keys work once the view has the focus, also after a click on a zoom button`(robot: FxRobot) {
        val view = fx { ImageFileViewer().open(png("photo.png", 400, 200)) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        robot.interact { view.focusText() }
        assertEquals(view.webView, fx { container.scene.focusOwner })

        click(view.zoom.zoomInButton)
        assertEquals(500.0, view.webView.imageWidth())
        assertEquals(view.webView, fx { container.scene.focusOwner }, "the button takes no focus")
        robot.press(fx { container.scene.focusOwner }, KeyCode.EQUALS, shortcut = true)
        assertEquals(600.0, view.webView.imageWidth())

        // Should the toolbar have the focus anyway, the keys work there too.
        robot.press(checkNotNull(view.zoom.fitButton), KeyCode.DIGIT9, shortcut = true)
        assertEquals(400.0, view.webView.imageWidth())
        assertTrue(fx { view.zoom.fit })

        // A scale the page reports that is no size is ignored.
        for (scale in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            robot.interact { view.zoom.zoomed(scale, fitted = false) }
            assertEquals(1.0, fx { view.zoom.scale })
            assertEquals("100%", fx { view.zoom.level.text })
            assertTrue(fx { view.zoom.fit })
        }
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
    fun `reload shows the changed image`(robot: FxRobot) {
        val file = png("icon.png", 10, 10)
        val view = fx { ImageFileViewer().open(file) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        png("icon.png", 30, 20)
        robot.interact { view.reload() }
        awaitLoaded(view.webView)

        assertEquals(30, (view.webView.script("document.querySelector('img').naturalWidth") as Number).toInt())
    }

    @Test
    fun `a disposed image view shows nothing more`(robot: FxRobot) {
        val view = fx { ImageFileViewer().open(png("icon.png", 10, 10)) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        robot.interact { view.dispose() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED } &&
                view.webView.script("document.querySelector('img')") == null
        }
        assertEquals("about:blank", fx { view.webView.engine.location })
        // The blank page is no image page: the colour scheme does not reach it.
        assertFalse(fx { view.isLoaded })
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        assertFalse(fx { view.isLoaded })
        assertEquals(false, view.webView.script("document.body.classList.contains('dark')"))

        // Neither a change of the file nor a page loaded into it shows the image again.
        png("icon.png", 30, 20)
        robot.interact { view.reload() }
        val other = dir.resolve("other.txt").apply { writeText("other") }
        robot.interact { view.webView.engine.load(other.toUri().toString()) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx {
                view.webView.engine.location
                    .orEmpty()
                    .endsWith("other.txt") &&
                    view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED
            }
        }
        // Nothing shows the image again: give a render, if there were one, the time to start.
        WaitForAsyncUtils.sleep(100, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(fx { view.webView.engine.location }.orEmpty().endsWith("other.txt"))
        assertEquals(null, view.webView.script("document.querySelector('img')"))
    }

    @Test
    fun `an unchanged file renders nothing again, a page dropped on the view is replaced, a file grown too large fails`(
        robot: FxRobot,
    ) {
        val file = png("icon.png", 10, 10)
        val view = fx { ImageFileViewer().open(file) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)
        var loads = 0
        robot.interact {
            view.webView.engine.loadWorker.stateProperty().subscribe { state ->
                if (state == Worker.State.RUNNING) loads++
            }
        }

        robot.interact { view.reload() }
        assertEquals(0, loads, "the same bytes load nothing")

        // Something dropped onto the page navigates; the image is shown again.
        val other = dir.resolve("other.txt").apply { writeText("other") }
        robot.interact { view.webView.engine.load(other.toUri().toString()) }
        awaitLoaded(view.webView)
        assertEquals(10, (view.webView.script("document.querySelector('img').naturalWidth") as Number).toInt())

        RandomAccessFile(file.toFile(), "rw").use { it.setLength(ImageFileViewer.MAX_IMAGE_BYTES + 1) }
        val failure = assertFailsWith<ExecutionException> { fx { view.reload() } }
        assertTrue(failure.cause is IOException, "${failure.cause}")

        assertTrue(fx { view.moved(dir.resolve("renamed.png")) }, "a name of the same image type")
        assertFalse(fx { view.moved(dir.resolve("renamed.jpg")) }, "another image type needs another view")
        robot.interact { view.dispose() }
        assertFalse(fx { view.moved(dir.resolve("again.png")) }, "a disposed view follows nothing")
    }

    @Test
    fun `the checkered background follows the colour scheme`(robot: FxRobot) {
        val view = fx { ImageFileViewer().open(png("icon.png", 10, 10)) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        assertEquals(true, view.webView.script("document.body.classList.contains('dark')"))
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.LIGHT }
        assertEquals(false, view.webView.script("document.body.classList.contains('dark')"))
    }

    @Test
    fun `an SVG shows as an image first, without running its scripts`(robot: FxRobot) {
        val svg =
            """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60" onload="top.document.title='ran'">""" +
                """<rect width="120" height="60" fill="red"/></svg>"""
        val file = dir.resolve("logo.svg").apply { writeText(svg) }
        val view = fx { SvgFileViewer().open(file) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        assertEquals(PreviewMode.PREVIEW, view.mode.get())
        assertEquals(120, (view.webView.script("document.querySelector('img').naturalWidth") as Number).toInt())
        assertEquals("", view.webView.script("document.title"))
    }

    @Test
    fun `an SVG zooms with the controls beside the preview, not beside the source alone`(robot: FxRobot) {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60"></svg>"""
        val view = fx { SvgFileViewer().open(dir.resolve("logo.svg").apply { writeText(svg) }) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)
        val zoom = view.zoom
        assertEquals(120.0, view.webView.imageWidth())

        robot.interact { zoom.zoomInButton.fire() }
        assertEquals(150.0, view.webView.imageWidth())
        robot.press(view.webView, KeyCode.DIGIT0, shortcut = true)
        assertEquals(120.0, view.webView.imageWidth())
        assertEquals("100%", fx { zoom.level.text })

        robot.interact { view.mode.set(PreviewMode.SOURCE) }
        assertFalse(fx { zoom.node.isVisible || zoom.node.isManaged })
        robot.interact { view.mode.set(PreviewMode.SPLIT) }
        assertTrue(fx { zoom.node.isVisible && zoom.node.isManaged })
    }
}
