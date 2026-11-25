package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageText
import dev.sluide.pdf.engine.PdfDocument

/**
 * The page texts of a view, extracted on its text worker (ADR 0192) and kept on the FX thread: texts with their glyph
 * boxes (about 1 MB a page) for the [capacity] pages used last, plain texts for every page asked for. A page that
 * cannot be read gives an empty text. Requests for a page on its way share one extraction. FX thread only.
 */
internal class PageTexts(
    private val worker: PdfWorker,
    private val capacity: Int = CAPACITY,
) {
    /** In the order of use, the one used last at the end. */
    private val texts = LinkedHashMap<Int, PageText>(16, 0.75f, true)
    private val plainTexts = HashMap<Int, String>()
    private val waitingTexts = HashMap<Int, MutableList<(PageText) -> Unit>>()
    private val waitingPlainTexts = HashMap<Int, MutableList<(String) -> Unit>>()

    /** Calls [onFx] with the text of [page] and its boxes: at once if it is kept, else once it was extracted. */
    fun textOf(
        page: Int,
        onFx: (PageText) -> Unit,
    ) = request(page, onFx, texts, capacity, waitingTexts, EMPTY, WorkPriority.TEXT) { it.pageText(page) }

    /** Calls [onFx] with the plain text of [page], equal to the text [textOf] gives: at once if it is kept. */
    fun plainTextOf(
        page: Int,
        onFx: (String) -> Unit,
    ) = request(
        page,
        onFx,
        plainTexts,
        Int.MAX_VALUE,
        waitingPlainTexts,
        "",
        WorkPriority.SEARCH,
    ) { it.plainText(page) }

    /**
     * Forgets every text and the requests on their way; call it with the text worker's reopen, which drops their
     * results.
     */
    fun clear() {
        texts.clear()
        plainTexts.clear()
        waitingTexts.clear()
        waitingPlainTexts.clear()
    }

    private fun <T> request(
        page: Int,
        onFx: (T) -> Unit,
        kept: MutableMap<Int, T>,
        limit: Int,
        waiting: MutableMap<Int, MutableList<(T) -> Unit>>,
        empty: T,
        priority: WorkPriority,
        extract: (PdfDocument) -> T,
    ) {
        kept[page]?.let {
            onFx(it)
            return
        }
        waiting[page]?.let {
            it += onFx
            return
        }
        val callbacks = mutableListOf(onFx)
        waiting[page] = callbacks

        fun arrived(value: T) {
            waiting.remove(page)
            kept[page] = value
            while (kept.size > limit) kept.remove(kept.keys.first())
            callbacks.forEach { it(value) }
        }
        worker.submit(priority, extract, ::arrived, { arrived(empty) })
    }

    companion object {
        /** How many texts with boxes are kept. */
        const val CAPACITY = 50

        private val EMPTY = PageText("", emptyList())
    }
}
