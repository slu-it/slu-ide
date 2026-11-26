package dev.sluide.previews.notebook

import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewMode
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** The notebook preview in a real WebView. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class NotebookPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun PreviewFileView.script(code: String): Any? =
        WaitForAsyncUtils.asyncFx<Any?> { webView.engine.executeScript(code) }.get()

    @Test
    fun `a notebook opens rendered, with its outputs`(robot: FxRobot) {
        val png =
            ByteArrayOutputStream().also {
                ImageIO.write(
                    BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB),
                    "png",
                    it,
                )
            }
        val image = Base64.getEncoder().encodeToString(png.toByteArray())
        val json =
            """{"metadata": {}, "cells": [{"cell_type": "code", "execution_count": 1, "source": "plot()", """ +
                """"outputs": [{"output_type": "display_data", "data": {"image/png": "$image"}}]}]}"""
        val file = dir.resolve("analysis.ipynb").apply { writeText(json) }
        val view =
            WaitForAsyncUtils.asyncFx<PreviewFileView> { NotebookFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(PreviewMode.PREVIEW, view.mode.get())
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) {
            view.script("document.querySelector('.output img').complete") == true
        }
        assertEquals(40, (view.script("document.querySelector('.output img').naturalWidth") as Number).toInt())
        assertEquals("In [1]:", view.script("document.querySelector('.prompt').textContent"))
    }

    @Test
    fun `a notebook's Markdown cell paints its PlantUML fence`(robot: FxRobot) {
        val json =
            """{"cells": [{"cell_type": "markdown", "source": "```plantuml\nAlice -> Bob : hello\n```\n"}]}"""
        val file = dir.resolve("diagram.ipynb").apply { writeText(json) }
        // The viewer as Spring creates it: the app's engine, results handed to the FX thread by Platform.runLater.
        val view =
            WaitForAsyncUtils.asyncFx<PreviewFileView> { NotebookFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }

        // The first drawing starts PlantUML, which is slow.
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) {
            WaitForAsyncUtils
                .asyncFx<Boolean> {
                    view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED
                }.get() &&
                view.script(
                    "(function () { var img = document.querySelector('.cell.markdown img.plantuml-light');" +
                        " return img !== null && img.complete && img.naturalWidth > 0; })()",
                ) == true
        }
        val box = "document.querySelector('img.plantuml-light').getBoundingClientRect()"

        /** The dark pixels inside the diagram, in a snapshot. */
        fun ink(): Int {
            val (left, top, right, bottom) =
                listOf("left", "top", "right", "bottom").map { (view.script("$box.$it") as Number).toInt() }
            val image = WaitForAsyncUtils.asyncFx<WritableImage> { view.webView.snapshot(null, null) }.get()
            return (left + 1 until minOf(right, image.width.toInt()) step 2).sumOf { x ->
                (top + 1 until minOf(bottom, image.height.toInt()) step 2).count { y ->
                    image.pixelReader.getColor(x, y).brightness < 0.4
                }
            }
        }
        // Loaded and decoded is not yet painted.
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { ink() > 20 }
    }
}
