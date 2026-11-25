package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PdfDocument
import dev.sluide.pdf.engine.RenderedPage
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The page images of a view ([I]: in the view a JavaFX image, which [convert] makes from the engine's pixels on the
 * worker, and [pixels] measures): rendered on its render worker in the order [update] is given, kept on the FX thread
 * within [budget] pixels (the farthest from the viewport dropped first), each kept until one at the new scale arrives,
 * so that a zoom shows the old one scaled meanwhile. A render no longer wanted is cancelled: dropped from the queue, or
 * stopped by its cancel check while it runs (no interrupt, ADR 0192). A page whose render failed (an `IOException` of
 * the engine, or no memory) is not tried again at that scale. [changed] is called with each page whose image or
 * failure changed. FX thread only.
 */
internal class PageImages<I : Any>(
    private val worker: PdfWorker,
    private val render: (PdfDocument, Int, Double, () -> Boolean) -> RenderedPage? = PdfDocument::render,
    private val convert: (RenderedPage) -> I,
    private val pixels: (I) -> Long,
    private val changed: (page: Int) -> Unit,
    private val budget: Long = PIXEL_BUDGET,
) {
    private class Request(
        val owner: PageImages<*>,
        val page: Int,
        val scale: Double,
    ) {
        val cancelled = AtomicBoolean()

        @Volatile
        var started = false
    }

    private class Shown<I>(
        val image: I,
        val scale: Double,
    )

    private val images = HashMap<Int, Shown<I>>()
    private val requests = HashMap<Int, Request>()
    private val failures = HashMap<Int, Double>()
    private var keep: Set<Int> = emptySet()
    private var centre = 0

    /** The image of [page], perhaps of another scale; `null` before it was rendered, and after it was dropped. */
    fun image(page: Int): I? = images[page]?.image

    /** Whether the last render of [page] failed. */
    fun failed(page: Int): Boolean = page in failures

    /**
     * Renders the pages of [order] at [scale] (render pixels per point), in that order, those it has at that scale
     * excepted; cancels the renders of other pages and scales, and re-queues those not started yet in the new order.
     * [centre] is the page the budget keeps images nearest to.
     */
    fun update(
        order: List<Pair<Int, WorkPriority>>,
        scale: Double,
        centre: Int,
    ) {
        val wanted = order.mapTo(HashSet()) { it.first }
        keep = wanted
        this.centre = centre
        requests.values.removeIf { request ->
            val drop = !request.started || request.page !in wanted || request.scale != scale
            if (drop) request.cancelled.set(true)
            drop
        }
        // Every render of this instance still queued was not started, so it was just cancelled. Other instances (the
        // thumbnails) share the worker.
        worker.cancelQueued { (it.tag as? Request)?.owner === this }
        for ((page, priority) in order) {
            if (images[page]?.scale != scale && failures[page] != scale && page !in requests) {
                submit(page, scale, priority)
            }
        }
    }

    /** Cancels every render and forgets every image and failure (the document was opened again, or the view closed). */
    fun clear() {
        requests.values.forEach { it.cancelled.set(true) }
        requests.clear()
        images.clear()
        failures.clear()
    }

    private fun submit(
        page: Int,
        scale: Double,
        priority: WorkPriority,
    ) {
        val request = Request(this, page, scale)
        requests[page] = request
        worker.submit(
            priority,
            { document ->
                request.started = true
                render(document, page, scale, request.cancelled::get)?.let(convert)
            },
            { image -> arrived(request, image) },
            { error -> failed(request, error) },
            tag = request,
        )
    }

    private fun arrived(
        request: Request,
        image: I?,
    ) {
        if (requests[request.page] !== request || image == null) return
        requests.remove(request.page)
        images[request.page] = Shown(image, request.scale)
        failures.remove(request.page)
        changed(request.page)
        imagesToDrop(images.mapValues { (_, shown) -> pixels(shown.image) }, keep, centre, budget).forEach(::drop)
    }

    private fun failed(
        request: Request,
        error: Throwable,
    ) {
        if (requests[request.page] !== request) return
        requests.remove(request.page)
        failures[request.page] = request.scale
        // Out of memory: keep only the images of the pages around the viewport.
        if (error is OutOfMemoryError) images.keys.filter { it !in keep }.forEach(::drop)
        changed(request.page)
    }

    private fun drop(page: Int) {
        images.remove(page)
        changed(page)
    }
}

/** Copies the engine's opaque ARGB pixels into a JavaFX image; on the worker, so that the FX thread only shows it. */
internal fun toImage(rendered: RenderedPage): Image =
    WritableImage(rendered.width, rendered.height).apply {
        pixelWriter.setPixels(
            0,
            0,
            rendered.width,
            rendered.height,
            PixelFormat.getIntArgbPreInstance(),
            rendered.pixels,
            0,
            rendered.width,
        )
    }

/** The pixels of [image]. */
internal fun pixelsOf(image: Image): Long = image.width.toLong() * image.height.toLong()
