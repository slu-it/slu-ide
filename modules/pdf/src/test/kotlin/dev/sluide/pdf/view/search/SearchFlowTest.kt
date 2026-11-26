package dev.sluide.pdf.view.search

import dev.sluide.pdf.PanelPdfs
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageText
import dev.sluide.pdf.view.ManualExecutor
import dev.sluide.pdf.view.ManualFx
import dev.sluide.pdf.view.OverlayLayer
import dev.sluide.pdf.view.OverlayLayer.SEARCH_CURRENT
import dev.sluide.pdf.view.OverlayLayer.SEARCH_HIT
import dev.sluide.pdf.view.PageOverlays
import dev.sluide.pdf.view.PageTexts
import dev.sluide.pdf.view.PdfWorker
import dev.sluide.pdf.view.textRects
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The search on real page texts of the fixtures, extracted by a text worker whose executor the test runs by hand. */
class SearchFlowTest {
    private val textExecutor = ManualExecutor()
    private val fx = ManualFx()
    private val worker = PdfWorker(textExecutor, fx)
    private val texts = PageTexts(worker)
    private val matcher = ManualExecutor()
    private var executorsMade = 0
    private var pageCount: Int? = 5
    private var shown = 0..0
    private val drawnPages = mutableListOf<Int>()
    private val overlays = PageOverlays { drawnPages += it }

    /** The texts with boxes the search gets; the view's own unless a test replaces them. */
    private var boxedTexts: (Int, (PageText) -> Unit) -> Unit = { page, onFx -> texts.textOf(page, onFx) }
    private val scrolls = mutableListOf<Pair<Int, PageRect>>()
    private val statuses = mutableListOf<String>()
    private var rows: List<SearchRow> = emptyList()
    private val plainRequests = mutableListOf<Int>()
    private val flow =
        SearchFlow(
            SearchHost(
                pageCount = { pageCount },
                plainTextOf = { page, onFx ->
                    plainRequests += page
                    texts.plainTextOf(page, onFx)
                },
                textOf = { page, onFx -> boxedTexts(page, onFx) },
                shownPages = { shown },
                overlays = overlays,
                scrollIntoView = { page, rect -> scrolls += page to rect },
                showStatus = { statuses += it },
                showRows = { rows = it },
            ),
            newExecutor = {
                executorsMade++
                matcher
            },
            onFx = fx,
        )

    private val status get() = statuses.lastOrNull().orEmpty()

    private fun open(file: Path = PanelPdfs.search) {
        texts.clear()
        worker.reopen(file, null, { }) { }
        run()
    }

    /** Runs the text worker, the matching and the FX runner until nothing is left. */
    private fun run() {
        repeat(5) {
            textExecutor.runAll()
            matcher.runAll()
            fx.runAll()
        }
    }

    @AfterTest
    fun dispose() {
        flow.dispose()
        worker.dispose()
        textExecutor.runAll()
    }

    private fun hits() = rows.filterIsInstance<SearchRow.Hit>().map { it.hit }

    /** The four hits of [PanelPdfs.SEARCH_WORD] in [PanelPdfs.search] listed now. */
    private fun wordHits(): List<SearchHit> = hits().also { assertEquals(4, it.size, rows.toString()) }

    private fun headings() = rows.filterIsInstance<SearchRow.Heading>().map { it.title }

    private fun textOf(page: Int): PageText {
        var text: PageText? = null
        texts.textOf(page) { text = it }
        run()
        return checkNotNull(text)
    }

    private fun rectsOf(hit: SearchHit) = textRects(textOf(hit.page), hit.start, hit.end)

    private fun layer(
        page: Int,
        layer: OverlayLayer,
    ) = overlays.on(page).filter { it.first == layer }.map { it.second }

    private fun searched(query: String = PanelPdfs.SEARCH_WORD) {
        flow.search(query)
        run()
    }

    @Test
    fun `collects the page texts in page order, counting them, then matches them on its executor`() {
        open()
        flow.search(PanelPdfs.SEARCH_WORD)
        assertEquals(listOf(0, 1, 2, 3, 4), plainRequests)
        assertEquals("Searching… page 0 of 5", status)
        textExecutor.runAll()
        fx.runAll()
        assertEquals(SEARCHING, status)
        assertEquals((0..4).map { "Searching… page $it of 5" }, statuses.filter { it.startsWith("Searching… page") })
        assertEquals(emptyList(), rows)
        matcher.runAll()
        fx.runAll()
        assertEquals("4 matches", status)
        assertEquals(listOf("Page 1", "Page 3", "Page 5"), headings())
        assertEquals(listOf(0, 2, 2, 4), hits().map { it.page })
        assertEquals(PanelPdfs.SEARCH_WORD, hits().first().match)
    }

    @Test
    fun `a second query on the same document finds the texts there and goes on to the matching at once`() {
        open()
        searched()
        val requests = plainRequests.size
        statuses.clear()
        flow.search("page")
        assertEquals(SEARCHING, status)
        run()
        assertEquals("2 matches", status)
        assertEquals(requests + 5, plainRequests.size)
        assertEquals(1, executorsMade)
    }

    @Test
    fun `a newer query replaces an older one, whose progress and results are dropped`() {
        open()
        flow.search(PanelPdfs.SEARCH_WORD)
        flow.search("page")
        statuses.clear()
        run()
        assertEquals((1..4).map { "Searching… page $it of 5" } + SEARCHING + "2 matches", statuses)
        assertEquals(listOf("Page 1", "Page 2"), headings())
    }

    @Test
    fun `clearing drops the search on its way and the results shown`() {
        open()
        searched()
        flow.search(PanelPdfs.SEARCH_WORD)
        flow.clear()
        run()
        assertEquals("", status)
        assertEquals(emptyList(), rows)
        assertTrue(overlays.on(0).isEmpty())
    }

    @Test
    fun `a query of fewer than two non-blank chars searches nothing and shows no status`() {
        open()
        searched("a ")
        assertEquals(emptyList(), plainRequests)
        assertEquals("", status)
        assertEquals(0, executorsMade)
    }

    @Test
    fun `without an open document nothing is searched, and the query is searched once one is open`() {
        pageCount = null
        open()
        searched()
        assertEquals(emptyList(), plainRequests)
        assertEquals("", status)
        pageCount = 5
        flow.documentChanged()
        run()
        assertEquals("4 matches", status)
    }

    @Test
    fun `a closed document clears the results and highlights`() {
        open()
        searched()
        assertTrue(overlays.on(0).isNotEmpty())
        pageCount = null
        flow.documentChanged()
        assertEquals("", status)
        assertEquals(emptyList(), rows)
        assertTrue(overlays.on(0).isEmpty())
    }

    @Test
    fun `a changed document is searched again with the last query, a cleared one is not`() {
        open()
        searched()
        open(PanelPdfs.searchMoved)
        flow.documentChanged()
        run()
        assertEquals("2 matches", status)
        assertEquals(listOf("Page 2", "Page 4"), headings())
        flow.clear()
        val requests = plainRequests.size
        flow.documentChanged()
        run()
        assertEquals(requests, plainRequests.size)
        assertEquals("", status)
    }

    @Test
    fun `a PDF whose pages have no text says so`() {
        pageCount = 1
        open(TestPdfs.blank)
        searched()
        assertEquals("This PDF has no text.", status)
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `no matches says so`() {
        open()
        searched("needle")
        assertEquals("No matches", status)
    }

    @Test
    fun `the hits on shown pages are highlighted, other pages once shown, and pages left keep theirs`() {
        open()
        searched()
        val (first, second, third, last) = wordHits()
        assertEquals(rectsOf(first), layer(0, SEARCH_HIT))
        assertEquals(1, layer(0, SEARCH_HIT).size)
        assertTrue(overlays.on(2).isEmpty() && overlays.on(4).isEmpty())
        shown = 2..4
        flow.shownPagesChanged()
        run()
        assertEquals(rectsOf(second) + rectsOf(third), layer(2, SEARCH_HIT))
        assertEquals(rectsOf(last), layer(4, SEARCH_HIT))
        assertEquals(rectsOf(first), layer(0, SEARCH_HIT))
        assertTrue(overlays.on(1).isEmpty() && overlays.on(3).isEmpty())
    }

    @Test
    fun `a change of the shown pages draws only the pages that came into view`() {
        open()
        shown = 2..2
        searched()
        assertEquals(setOf(2), drawnPages.toSet())
        drawnPages.clear()
        shown = 0..4
        flow.shownPagesChanged()
        run()
        assertEquals(setOf(0, 4), drawnPages.toSet())
    }

    @Test
    fun `a chosen hit whose page gives no text with boxes is not scrolled to`() {
        open()
        searched()
        // PageTexts answers an empty text when the extraction with boxes fails (out of memory) after the plain text came.
        boxedTexts = { _, onFx -> onFx(PageText("", emptyList())) }
        flow.choose(wordHits()[1])
        run()
        assertEquals(emptyList(), scrolls)
    }

    @Test
    fun `a new search removes both layers at once`() {
        open()
        searched()
        flow.choose(wordHits().first())
        run()
        flow.search("nothing")
        assertTrue(overlays.on(0).isEmpty())
    }

    @Test
    fun `a chosen hit is current, scrolled into view, and goes back to a hit when another is chosen`() {
        open()
        searched()
        val (first, second, third) = wordHits()
        flow.choose(third)
        run()
        assertEquals(rectsOf(third), layer(2, SEARCH_CURRENT))
        assertEquals(rectsOf(second), layer(2, SEARCH_HIT))
        assertEquals(listOf(2 to checkNotNull(bounds(rectsOf(third)))), scrolls)
        flow.choose(first)
        run()
        assertEquals(rectsOf(first), layer(0, SEARCH_CURRENT))
        assertTrue(layer(0, SEARCH_HIT).isEmpty())
        assertEquals(rectsOf(second) + rectsOf(third), layer(2, SEARCH_HIT))
        assertTrue(layer(2, SEARCH_CURRENT).isEmpty())
        assertEquals(0, scrolls.last().first)
    }

    @Test
    fun `highlights and scrolls arriving after the search changed are dropped`() {
        open()
        flow.search(PanelPdfs.SEARCH_WORD)
        textExecutor.runAll()
        fx.runAll()
        matcher.runAll()
        // Shows the hits and asks for the text of page 1 with its boxes, which the text worker has not given yet.
        fx.runAll()
        flow.choose(wordHits().last())
        flow.clear()
        run()
        assertTrue(overlays.on(0).isEmpty() && overlays.on(4).isEmpty())
        assertEquals(emptyList(), scrolls)
    }

    @Test
    fun `the executor is made for the first matching, and dispose drops the matching on its way and shuts it down`() {
        open()
        flow.search(PanelPdfs.SEARCH_WORD)
        assertEquals(0, executorsMade)
        textExecutor.runAll()
        fx.runAll()
        assertEquals(1, executorsMade)
        flow.dispose()
        matcher.runAll()
        fx.runAll()
        assertEquals(emptyList(), rows)
        assertFalse("4 matches" in statuses)
        assertTrue(matcher.isShutdown)
        assertFalse(matcher.shutDownNow)
    }

    @Test
    fun `shown pages and a choice before any results highlight and scroll nothing`() {
        open()
        flow.shownPagesChanged()
        flow.choose(SearchHit(0, 2, 9, "", PanelPdfs.SEARCH_WORD, ""))
        run()
        assertTrue(overlays.on(0).isEmpty())
        assertEquals(emptyList(), scrolls)
    }

    @Test
    fun `dispose without a search makes no executor`() {
        flow.dispose()
        assertEquals(0, executorsMade)
    }
}
