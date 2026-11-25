package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.PageSize
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The thumbnail images of the side panel ([I]: in the view a JavaFX image) and the renders on their way: [show] asks
 * [submit] for the shown pages it has no image of, and cancels the requests of the pages no longer shown (their
 * `cancelled` flag, then [dropCancelled] takes the queued ones off the worker). At most [capacity] images are kept,
 * the least recently used dropped first. A page whose render failed gets no image and is not asked for again until
 * [clear]. [changed] is called with each page whose image arrived. FX-free; call it from one thread (the FX thread).
 */
internal class ThumbnailCache<I : Any>(
    private val submit: (Request<I>) -> Unit,
    private val dropCancelled: () -> Unit,
    private val changed: (page: Int) -> Unit,
    private val capacity: Int = CAPACITY,
) {
    /**
     * A render of [page] on its way: the worker gives its image to [arrived] (`null` when it was cancelled) or its
     * failure to [failed]; a render checks [cancelled] while it runs.
     */
    class Request<I : Any> internal constructor(
        private val cache: ThumbnailCache<I>,
        val page: Int,
    ) {
        val cancelled = AtomicBoolean()

        fun arrived(image: I?) = cache.arrived(this, image)

        fun failed() = cache.failed(this)
    }

    /** The images by page, in the order of their last use. */
    private val images = LinkedHashMap<Int, I>(INITIAL_SIZE, LOAD_FACTOR, true)
    private val requests = HashMap<Int, Request<I>>()
    private val failures = HashSet<Int>()

    /** The image of [page], `null` before it arrived and after it was dropped; counts as a use. */
    fun image(page: Int): I? = images[page]

    /** Renders the [pages] shown that have no image yet, failed ones excepted; cancels the renders of other pages. */
    fun show(pages: Iterable<Int>) {
        val shown = pages.toSet()
        cancel { it.page !in shown }
        for (page in pages) {
            if (page !in images && page !in failures && page !in requests) {
                Request(this, page).also { requests[page] = it }.let(submit)
            }
        }
    }

    /** Cancels every render and forgets every image and failure (the document was opened again, or the view closed). */
    fun clear() {
        cancel { true }
        images.clear()
        failures.clear()
    }

    /** Cancels the requests [which] matches and drops them from the queue, if there were any. */
    private fun cancel(which: (Request<I>) -> Boolean) {
        val stale = requests.values.filter(which)
        if (stale.isEmpty()) return
        for (request in stale) {
            request.cancelled.set(true)
            requests.remove(request.page)
        }
        dropCancelled()
    }

    private fun arrived(
        request: Request<I>,
        image: I?,
    ) {
        if (requests[request.page] !== request) return
        requests.remove(request.page)
        if (image == null) return
        images[request.page] = image
        if (images.size > capacity) images.remove(images.keys.first())
        changed(request.page)
    }

    private fun failed(request: Request<I>) {
        if (requests[request.page] !== request) return
        requests.remove(request.page)
        failures += request.page
    }

    companion object {
        const val CAPACITY = 200
        private const val INITIAL_SIZE = 16
        private const val LOAD_FACTOR = 0.75f
    }
}

/** The width of a thumbnail, in pixels at an output scale of 1. */
internal const val THUMBNAIL_WIDTH = 120.0

/** The height a thumbnail never passes (a long, thin page), in pixels at an output scale of 1. */
internal const val THUMBNAIL_MAX_HEIGHT = 480.0

/**
 * The scale (thumbnail pixels per point, at an output scale of 1) of the thumbnail of a page of [size]:
 * [THUMBNAIL_WIDTH] wide, unless that would make it higher than [THUMBNAIL_MAX_HEIGHT]; 1 for a page of 0 by 0 points.
 */
internal fun thumbnailScale(size: PageSize): Double {
    val scale = minOf(THUMBNAIL_WIDTH / size.width, THUMBNAIL_MAX_HEIGHT / size.height)
    return if (scale.isFinite()) scale else 1.0
}

/** The scale a thumbnail of a page of [size] renders at on a screen of [outputScale]: crisp on a Retina screen too. */
internal fun thumbnailRenderScale(
    size: PageSize,
    outputScale: Double,
): Double = thumbnailScale(size) * outputScale
