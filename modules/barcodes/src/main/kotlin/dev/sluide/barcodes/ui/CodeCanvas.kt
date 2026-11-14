package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.Layout
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Region

/** What the [CodeCanvas] draws. */
internal sealed interface CanvasContent {
    data object Empty : CanvasContent

    /** A generated [code] in its colours; [dimmed] while the text shows an error and the code is the last good one. */
    data class Generated(
        val code: dev.sluide.barcodes.engine.Generated.Code,
        val options: GenerateOptions,
        val dimmed: Boolean,
    ) : CanvasContent

    /** A read image, fitted. */
    data class Picture(
        val image: ArgbImage,
    ) : CanvasContent
}

/**
 * The image side's canvas: draws [content] centred and redraws when it is resized. A code is drawn from its module grid
 * with a whole number of pixels per module where that fits, never by scaling a bitmap. The canvas itself is
 * transparent; the pane around it, styled by the theme, is the background.
 */
internal class CodeCanvas : Region() {
    internal val canvas = Canvas().apply { id = "barcodes-canvas" }

    var content: CanvasContent = CanvasContent.Empty
        set(value) {
            field = value
            draw()
        }

    init {
        children += canvas
        minWidth = 0.0
        minHeight = 0.0
        prefWidth = 360.0
        prefHeight = 360.0
    }

    override fun layoutChildren() {
        canvas.width = width
        canvas.height = height
        draw()
    }

    /** Device pixels per logical pixel of the window the canvas is in (1 until it is in one). */
    private val renderX get() = scene?.window?.renderScaleX ?: 1.0
    private val renderY get() = scene?.window?.renderScaleY ?: 1.0

    private fun draw() {
        val gc = canvas.graphicsContext2D
        gc.clearRect(0.0, 0.0, canvas.width, canvas.height)
        when (val shown = content) {
            CanvasContent.Empty -> Unit
            is CanvasContent.Generated -> drawCode(gc, shown)
            is CanvasContent.Picture -> drawPicture(gc, shown.image)
        }
    }

    private fun drawCode(
        gc: GraphicsContext,
        shown: CanvasContent.Generated,
    ) {
        val code = shown.code
        val options = shown.options
        val layout = Layout.of(code, options)
        val fit =
            fit(
                layout.widthModules,
                layout.heightModules,
                canvas.width,
                canvas.height,
                wholePixels = true,
                renderScaleX = renderX,
                renderScaleY = renderY,
            )
                ?: return
        gc.globalAlpha = if (shown.dimmed) DIMMED else 1.0
        gc.fill = colorOf(options.background)
        gc.fillRect(fit.x, fit.y, fit.width, fit.height)
        gc.fill = colorOf(options.foreground)
        val is1D = code.format.is1D
        for (row in 0 until code.grid.height) {
            var column = 0
            while (column < code.grid.width) {
                if (!code.grid[column, row]) {
                    column++
                    continue
                }
                val start = column
                while (column < code.grid.width && code.grid[column, row]) column++
                val top = if (is1D) 0 else row + layout.quietY
                val rows = if (is1D) layout.heightModules else 1
                gc.fillRect(
                    fit.x + (start + layout.quietX) * fit.scale,
                    fit.y + top * fit.scale,
                    (column - start) * fit.scale,
                    rows * fit.scale,
                )
            }
        }
        gc.globalAlpha = 1.0
    }

    /** The place P4 fills in: the read image with the outlines of its codes. Now only the image, fitted. */
    private fun drawPicture(
        gc: GraphicsContext,
        image: ArgbImage,
    ) {
        if (image.width == 0 || image.height == 0) return
        val fit =
            fit(
                image.width,
                image.height,
                canvas.width,
                canvas.height,
                wholePixels = false,
                renderScaleX = renderX,
                renderScaleY = renderY,
            )
                ?: return
        val pixels = WritableImage(image.width, image.height)
        pixels.pixelWriter.setPixels(
            0,
            0,
            image.width,
            image.height,
            PixelFormat.getIntArgbInstance(),
            image.argb,
            0,
            image.width,
        )
        gc.drawImage(pixels, fit.x, fit.y, fit.width, fit.height)
    }

    private companion object {
        const val DIMMED = 0.35
    }
}
