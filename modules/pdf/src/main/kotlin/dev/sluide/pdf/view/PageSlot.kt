package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageRect
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane

/**
 * The place of one page in the column: a white page (style class `pdf-page`) of the page's size that shows its image
 * once rendered (scaled to the slot, so an image of the old scale fills it until the new one arrives), the overlays
 * above it, or "Could not show page N." when its render failed. FX thread only.
 */
internal class PageSlot(
    page: Int,
) : StackPane() {
    private val imageView = ImageView().apply { isSmooth = true }
    private val overlay =
        Pane().apply {
            styleClass += "pdf-page-overlays"
            isMouseTransparent = true
        }
    val failure =
        Label("Could not show page ${page + 1}.").apply {
            styleClass += "pdf-page-failure"
            isVisible = false
        }

    init {
        styleClass += "pdf-page"
        children.setAll(imageView, overlay, failure)
    }

    /** The image shown; `null` while the page is blank. */
    val image: Image? get() = imageView.image

    /** The overlays' rects in points, and the scale they are laid out at. */
    private var rects: List<Pair<OverlayLayer, PageRect>> = emptyList()
    private var scale = 1.0

    /** Puts the slot at [x], [y] in the column, [width] by [height], its overlays at [scale]. */
    fun place(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        scale: Double,
    ) {
        this.scale = scale
        placeOverlays()
        relocate(x, y)
        setMinSize(width, height)
        setPrefSize(width, height)
        setMaxSize(width, height)
        imageView.fitWidth = width
        imageView.fitHeight = height
    }

    /** Shows [image], or the failure text instead when [failed]. */
    fun show(
        image: Image?,
        failed: Boolean,
    ) {
        imageView.image = if (failed) null else image
        failure.isVisible = failed
    }

    /**
     * Draws [rects] (points) in their order, each a region with its layer's style class. A zoom only moves them
     * ([place]): regions made anew in a layout pass, which a resize is, would get their style a pulse late.
     */
    fun drawOverlays(rects: List<Pair<OverlayLayer, PageRect>>) {
        this.rects = rects
        overlay.children.setAll(
            rects.map { (layer, _) ->
                Region().apply {
                    styleClass += layer.styleClass
                    isManaged = false
                }
            },
        )
        placeOverlays()
    }

    private fun placeOverlays() {
        overlay.children.zip(rects) { region, (_, rect) ->
            (region as Region).resizeRelocate(rect.x * scale, rect.y * scale, rect.width * scale, rect.height * scale)
        }
    }

    /** The overlay regions drawn, for tests. */
    internal val overlays: List<Region> get() = overlay.children.map { it as Region }
}
