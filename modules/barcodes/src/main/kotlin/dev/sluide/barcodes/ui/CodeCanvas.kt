package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.DecodedCode
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.Layout
import javafx.geometry.VPos
import javafx.scene.Cursor
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment

/** What the [CodeCanvas] draws. */
internal sealed interface CanvasContent {
    data object Empty : CanvasContent

    /** A generated [code] in its colours; [dimmed] while the text shows an error and the code is the last good one. */
    data class Generated(
        val code: dev.sluide.barcodes.engine.Generated.Code,
        val options: GenerateOptions,
        val dimmed: Boolean,
    ) : CanvasContent

    /** A read image, fitted, with the outlines of its [codes] and the [selected] one marked. */
    data class Picture(
        val image: ArgbImage,
        val codes: List<DecodedCode>,
        val selected: Int,
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

    /** Called with the index of the code whose outline was clicked. */
    var onPick: (Int) -> Unit = {}

    private var cached: Pair<ArgbImage, WritableImage>? = null

    /** Whether the bitmap of a read image is held. */
    internal val holdsPicture get() = cached != null

    /** How often a bitmap was built for a picture; one per read image, not one per drawing. */
    internal var picturesBuilt = 0
        private set

    init {
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED) {
            cursor =
                if (pickAt(it) != null) Cursor.HAND else Cursor.DEFAULT
        }
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED) { cursor = Cursor.DEFAULT }
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) {
            if (it.button == MouseButton.PRIMARY && it.isStillSincePress) pickAt(it)?.let(onPick)
        }
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
        // The bitmap of a read image is released as soon as something else is shown.
        if (content !is CanvasContent.Picture) cached = null
        when (val shown = content) {
            CanvasContent.Empty -> Unit
            is CanvasContent.Generated -> drawCode(gc, shown)
            is CanvasContent.Picture -> drawPicture(gc, shown)
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

    private fun pictureFit(image: ArgbImage) =
        fit(
            image.width,
            image.height,
            canvas.width,
            canvas.height,
            wholePixels = false,
            renderScaleX = renderX,
            renderScaleY = renderY,
        )

    /** The code to pick at the event's point, only while there are two or more (with one there is nothing to pick). */
    private fun pickAt(event: MouseEvent): Int? {
        val shown = content as? CanvasContent.Picture ?: return null
        if (shown.codes.size < 2) return null
        val placed = pictureFit(shown.image) ?: return null
        return hitTest(event.x, event.y, shown.codes.map { it.bounds }, placed)
    }

    private fun bitmapOf(image: ArgbImage): WritableImage {
        cached?.takeIf { it.first === image }?.let { return it.second }
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
        picturesBuilt++
        cached = image to pixels
        return pixels
    }

    private fun drawPicture(
        gc: GraphicsContext,
        shown: CanvasContent.Picture,
    ) {
        val image = shown.image
        if (image.width == 0 || image.height == 0) return
        val placed = pictureFit(image) ?: return
        gc.isImageSmoothing = true
        gc.drawImage(bitmapOf(image), placed.x, placed.y, placed.width, placed.height)
        // The selected code last, so its outline is on top.
        val order = shown.codes.indices.sortedBy { it == shown.selected }
        for (index in order) {
            val rect = imageToCanvas(shown.codes[index].bounds, placed)
            val selected = index == shown.selected
            outline(gc, rect, selected)
            if (shown.codes.size > 1) badge(gc, rect, index + 1, selected)
        }
    }

    /** A dark halo under the coloured stroke, so the outline shows on light and dark images. */
    private fun outline(
        gc: GraphicsContext,
        rect: Rect,
        selected: Boolean,
    ) {
        val width = if (selected) SELECTED_STROKE else OTHER_STROKE
        gc.stroke = HALO
        gc.lineWidth = width + 2
        gc.strokeRect(rect.x, rect.y, rect.width, rect.height)
        gc.stroke = if (selected) ACCENT else ACCENT_OTHERS
        gc.lineWidth = width
        gc.strokeRect(rect.x, rect.y, rect.width, rect.height)
    }

    /** The code's number in a small filled badge at the top-left corner, above the outline where there is room. */
    private fun badge(
        gc: GraphicsContext,
        rect: Rect,
        number: Int,
        selected: Boolean,
    ) {
        val top = if (rect.y - BADGE >= 0) rect.y - BADGE else rect.y
        gc.fill = HALO
        gc.fillRoundRect(rect.x - 1, top - 1, BADGE + 2, BADGE + 2, 6.0, 6.0)
        gc.fill = if (selected) ACCENT else ACCENT_OTHERS
        gc.fillRoundRect(rect.x, top, BADGE, BADGE, 5.0, 5.0)
        gc.fill = Color.WHITE
        gc.font = Font.font("System", FontWeight.BOLD, 12.0)
        gc.textAlign = TextAlignment.CENTER
        gc.textBaseline = VPos.CENTER
        gc.fillText(number.toString(), rect.x + BADGE / 2, top + BADGE / 2)
    }

    private companion object {
        const val DIMMED = 0.35
        const val SELECTED_STROKE = 3.0
        const val OTHER_STROKE = 2.0
        const val BADGE = 18.0

        // Fixed colours, the same in both themes, as the code colours are: the halo makes them show on any image.
        val ACCENT: Color = Color.web("#2d8cff")
        val ACCENT_OTHERS: Color = Color.web("#2d8cff", 0.6)
        val HALO: Color = Color.web("#000000", 0.55)
    }
}
