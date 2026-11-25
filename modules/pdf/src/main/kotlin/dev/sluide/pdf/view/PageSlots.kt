package dev.sluide.pdf.view

import javafx.scene.layout.Pane

/**
 * The [PageSlot]s in the page column ([content]): only the pages around the viewport have one. Their images come from
 * [PageImages] on the render worker, their highlights from [overlays]. FX thread only.
 */
internal class PageSlots(
    private val content: Pane,
    renderWorker: PdfWorker,
) {
    private val images = PageImages(renderWorker, convert = ::toImage, pixels = ::pixelsOf, changed = ::showImage)
    val overlays = PageOverlays(::drawOverlays)
    private val slots = HashMap<Int, PageSlot>()
    private var layout: PageLayout? = null
    private var columnWidth = 0.0

    /** The slots that exist, by page. */
    val all: Map<Int, PageSlot> get() = slots

    /** Places the slots as [layout] lays out the pages in a column [columnWidth] wide. */
    fun place(
        layout: PageLayout,
        columnWidth: Double,
    ) {
        this.layout = layout
        this.columnWidth = columnWidth
        slots.forEach(::placeSlot)
    }

    /**
     * Gives the pages of [order] a slot and drops the other slots, then renders the pages of [order] at [renderScale]
     * (see [PageImages.update]; [centre] is the page the pixel budget keeps images nearest to).
     */
    fun show(
        order: List<Pair<Int, WorkPriority>>,
        renderScale: Double,
        centre: Int,
    ) {
        val wanted = order.mapTo(HashSet()) { it.first }
        slots.keys.filter { it !in wanted }.forEach { content.children.remove(slots.remove(it)) }
        for (page in wanted) {
            if (page !in slots) {
                val slot = PageSlot(page)
                slots[page] = slot
                content.children += slot
                slot.drawOverlays(overlays.on(page))
                placeSlot(page, slot)
            }
        }
        images.update(order, renderScale, centre)
    }

    /** Drops every slot, cancels the renders and forgets the images. */
    fun clear() {
        images.clear()
        slots.clear()
        content.children.clear()
        layout = null
    }

    /** Releases the images the slots show, then clears. */
    fun release() {
        slots.values.forEach { it.show(null, false) }
        clear()
    }

    private fun placeSlot(
        page: Int,
        slot: PageSlot,
    ) {
        val shown = layout ?: return
        slot.place(
            shown.left(page, columnWidth),
            shown.top(page),
            shown.pageWidth(page),
            shown.pageHeight(page),
            shown.scale,
        )
        slot.show(images.image(page), images.failed(page))
    }

    private fun showImage(page: Int) {
        slots[page]?.show(images.image(page), images.failed(page))
    }

    private fun drawOverlays(page: Int) {
        slots[page]?.drawOverlays(overlays.on(page))
    }
}
