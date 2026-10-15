package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
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
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.fileSize
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
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

        assertEquals("2000 × 100 px · ${humanSize(file.fileSize())}", view.status.text)
        val fitted = view.webView.imageWidth()
        assertTrue(fitted < 600, "fitted: $fitted")
        assertTrue(fx { view.zoom.fit && view.zoom.fitButton.isSelected })
        assertEquals(ZoomSteps.label(fitted / 2000), fx { view.zoom.level.text })

        view.webView.script("document.body.click()")
        assertEquals(2000.0, view.webView.imageWidth())
        assertFalse(fx { view.zoom.fit || view.zoom.fitButton.isSelected })
        assertEquals("100%", fx { view.zoom.level.text })

        view.webView.script("document.body.click()")
        assertEquals(fitted, view.webView.imageWidth())
        assertTrue(fx { view.zoom.fit })
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

        robot.interact { view.zoom.fitButton.fire() }
        assertEquals(400.0, view.webView.imageWidth())
        assertTrue(fx { view.zoom.fit && view.zoom.fitButton.isSelected })
        robot.interact { view.zoom.fitButton.fire() }
        assertTrue(fx { view.zoom.fitButton.isSelected }, "Fit stays selected")

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
        robot.press(view.zoom.fitButton, KeyCode.DIGIT9, shortcut = true)
        assertEquals(400.0, view.webView.imageWidth())
        assertTrue(fx { view.zoom.fit })
    }

    @Test
    fun `a scale the page reports that is no size is ignored`(robot: FxRobot) {
        val view = fx { ImageFileViewer().open(png("icon.png", 10, 10)) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

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
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(fx { view.webView.engine.location }.orEmpty().endsWith("other.txt"))
        assertEquals(null, view.webView.script("document.querySelector('img')"))
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
}
