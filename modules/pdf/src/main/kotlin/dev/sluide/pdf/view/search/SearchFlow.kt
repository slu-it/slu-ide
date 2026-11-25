package dev.sluide.pdf.view.search

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageText
import dev.sluide.pdf.view.OverlayLayer
import dev.sluide.pdf.view.PageOverlays
import dev.sluide.pdf.view.textRects
import javafx.application.Platform
import java.util.concurrent.ExecutorService

/**
 * What the search uses of the PDF view (ADR 0084: an FX-free host the tests build without a scene): the page count
 * while a document is open, its page texts, the shown pages, the highlights and the scroll, and the panel's status
 * and rows.
 */
internal class SearchHost(
    val pageCount: () -> Int?,
    val plainTextOf: (Int, (String) -> Unit) -> Unit,
    val textOf: (Int, (PageText) -> Unit) -> Unit,
    val shownPages: () -> IntRange,
    val overlays: PageOverlays,
    val scrollIntoView: (Int, PageRect) -> Unit,
    val showStatus: (String) -> Unit,
    val showRows: (List<SearchRow>) -> Unit,
)

/**
 * The search of a PDF view (ADR 0192): collects the plain texts of all pages (in page order; the view keeps them, so
 * a second query on the same document finds them there), matches them on an executor of its own and shows the hits.
 * The hits on shown pages are highlighted, the chosen one more strongly. A newer [search], [clear] or [dispose] drops
 * what an older search would still deliver. Without an open document nothing is searched; [documentChanged] searches
 * the last query again. FX thread only, except the matching.
 */
internal class SearchFlow(
    private val host: SearchHost,
    private val newExecutor: () -> ExecutorService = { NamedThreads.single("sluide-pdf-search") },
    onFx: (Runnable) -> Unit = Platform::runLater,
) {
    /** The executor of the matching, made for the first one. */
    private var executor: ExecutorService? = null
    private val runner = SearchRunner({ task -> matcher().execute(task) }, onFx)

    /** The query searched last, searched again by [documentChanged]; `null` after [clear]. */
    private var query: String? = null

    /** Counts the searches; texts arriving for an older one are dropped. */
    private var generation = 0

    /** The highlights of the results shown; `null` while none are. */
    private var highlights: SearchHighlights? = null

    /** The pages shown when the shown pages were last looked at; those of them with hits are drawn already. */
    private var shown = IntRange.EMPTY

    /** Searches [query] in place of what was searched before. */
    fun search(query: String) {
        reset()
        this.query = query
        // Without a document the text worker has none either and would give every page an empty text.
        val pages = host.pageCount() ?: return
        if (!PdfSearch.searchable(query)) return
        val mine = generation
        val texts = Array(pages) { "" }
        var arrived = 0
        host.showStatus(collectingStatus(0, pages))
        for (page in 0 until pages) {
            host.plainTextOf(page) { text ->
                if (mine != generation) return@plainTextOf
                texts[page] = text
                arrived++
                if (arrived < pages) host.showStatus(collectingStatus(arrived, pages)) else match(texts.toList(), query)
            }
        }
    }

    /** Removes the results and highlights and forgets the query (it changed, or the panel closed). */
    fun clear() {
        reset()
        query = null
    }

    /** The document was opened again, or closed: searches the last query again on it, if there is one. */
    fun documentChanged() {
        query?.let(::search)
    }

    /** Highlights the hits on the pages that came into view; pages no longer shown keep theirs. */
    fun shownPagesChanged() {
        val before = shown
        shown = host.shownPages()
        val current = highlights ?: return
        current.pagesWithHits(shown).filter { it !in before }.forEach { draw(current, it) }
    }

    /** Makes [hit] the current hit and scrolls it into view. */
    fun choose(hit: SearchHit) {
        val current = highlights ?: return
        current.choose(hit).forEach { draw(current, it) }
        host.textOf(hit.page) { text ->
            if (highlights !== current) return@textOf
            bounds(textRects(text, hit.start, hit.end))?.let { host.scrollIntoView(hit.page, it) }
        }
    }

    /** Drops what is on its way and shuts the executor down, if one was made. */
    fun dispose() {
        reset()
        executor?.shutdown()
    }

    private fun matcher(): ExecutorService = executor ?: newExecutor().also { executor = it }

    /** Matches the [texts] of all pages on the executor; the newest search's result is shown. */
    private fun match(
        texts: List<String>,
        query: String,
    ) {
        host.showStatus(SEARCHING)
        runner.submit({ PdfSearch.find(texts, query) }, ::show)
    }

    private fun show(result: SearchResult) {
        host.showRows(searchRows(result))
        host.showStatus(searchStatus(result))
        val current = SearchHighlights(result.hits)
        highlights = current
        shown = host.shownPages()
        current.pagesWithHits(shown).forEach { draw(current, it) }
    }

    /** Sets the layers of [page] once its text with boxes is there. */
    private fun draw(
        current: SearchHighlights,
        page: Int,
    ) = host.textOf(page) { text ->
        if (highlights !== current) return@textOf
        for ((layer, hits) in current.layers(page)) {
            host.overlays.set(layer, page, hits.flatMap { textRects(text, it.start, it.end) })
        }
    }

    /** Drops the search on its way, the results and the highlights. */
    private fun reset() {
        generation++
        runner.cancel()
        highlights = null
        host.overlays.clear(OverlayLayer.SEARCH_HIT)
        host.overlays.clear(OverlayLayer.SEARCH_CURRENT)
        host.showRows(emptyList())
        host.showStatus("")
    }
}
