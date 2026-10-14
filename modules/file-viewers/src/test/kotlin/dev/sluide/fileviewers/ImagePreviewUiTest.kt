package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Scene
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

    @Test
    fun `shows an image with its pixel and file size, fitted or at actual size`(robot: FxRobot) {
        val file = png("wide.png", 2000, 100)
        val view = fx { ImageFileViewer().open(file) as ImageFileView }
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view.webView)

        assertEquals("2000 × 100 px · ${humanSize(file.fileSize())}", view.status.text)
        val fitted = (view.webView.script("document.querySelector('img').width") as Number).toInt()
        assert(fitted < 600) { "fitted: $fitted" }

        view.webView.script("document.body.click()")
        assertEquals(2000, (view.webView.script("document.querySelector('img').width") as Number).toInt())
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
        // Changing the colour scheme no longer reaches the blank page.
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        assertEquals(false, view.webView.script("document.body.classList.contains('dark')"))
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
