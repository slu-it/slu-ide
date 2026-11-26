package dev.sluide.pdf

import dev.sluide.pdf.TestPdfs.lines
import dev.sluide.pdf.engine.PageRect
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import java.nio.file.Path

/**
 * The PDFs of the view's panels (selection and links, the side panel, search), built with PDFBox by [TestPdfs]'
 * helpers; split off [TestPdfs] to keep each file under the size the tests allow.
 */
object PanelPdfs {
    // Side panel: a nested outline over seven pages, and 20 pages without an outline.

    /**
     * Seven numbered pages (0-based 0..6) and this outline, items open unless said: "1 Intro" (page 0) with "1.1 Start"
     * (page 0) and "1.2 More" (page 1); "2 Body" (page 2, closed) with "2.1 Hidden" (page 3); "3 No destination";
     * "4 Late" (page 4) with "4.1 Deep" (page 5); "5 End" (page 6).
     */
    val sideOutline: Path by lazy { TestPdfs.build("side-outline.pdf") { sideOutlineDocument(it, prefix = "") } }

    /** [sideOutline] with "New " before each title. */
    val sideOutlineRenamed: Path by lazy {
        TestPdfs.build(
            "side-outline-renamed.pdf",
        ) { sideOutlineDocument(it, "New ") }
    }

    const val SIDE_PAGES = 20

    /** [SIDE_PAGES] numbered pages without an outline. */
    val sideNoOutline: Path by lazy { TestPdfs.build("side-no-outline.pdf") { TestPdfs.numberedPages(it, SIDE_PAGES) } }

    /** Two blank pages: 612 by 792 points, then 792 by 612. */
    val sidePortraitLandscape: Path by lazy {
        TestPdfs.build("side-portrait-landscape.pdf") { document ->
            TestPdfs.page(document, PDRectangle(612f, 792f))
            TestPdfs.page(document, PDRectangle(792f, 612f))
        }
    }

    const val SIDE_LONG_PAGES = 60

    /** The title of the last item of [sideLongOutline]: on two lines, and longer than a side panel is wide. */
    const val SIDE_LONG_TITLE = "Page 60:\nthe last page, whose title is far longer than the side panel is wide"

    /**
     * [SIDE_LONG_PAGES] numbered pages and an outline item "Page N" for each but the first, more than a side panel shows
     * at once; the last is titled [SIDE_LONG_TITLE].
     */
    val sideLongOutline: Path by lazy {
        TestPdfs.build("side-long-outline.pdf") { document ->
            TestPdfs.numberedPages(document, SIDE_LONG_PAGES)
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            for (page in 1 until SIDE_LONG_PAGES - 1) {
                outline.addLast(
                    TestPdfs.item("Page ${page + 1}", TestPdfs.fit(document.getPage(page))),
                )
            }
            outline.addLast(TestPdfs.item(SIDE_LONG_TITLE, TestPdfs.fit(document.getPage(SIDE_LONG_PAGES - 1))))
            outline.openNode()
        }
    }

    private fun sideOutlineDocument(
        document: PDDocument,
        prefix: String,
    ) {
        TestPdfs.numberedPages(document, 7)

        fun to(
            title: String,
            page: Int?,
        ) = TestPdfs.item(prefix + title, page?.let { TestPdfs.fit(document.getPage(it)) })
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        val intro = to("1 Intro", 0).also { it.addLast(to("1.1 Start", 0)) }
        intro.addLast(to("1.2 More", 1))
        val body = to("2 Body", 2).also { it.addLast(to("2.1 Hidden", 3)) }
        val late = to("4 Late", 4).also { it.addLast(to("4.1 Deep", 5)) }
        listOf(intro, body, to("3 No destination", null), late, to("5 End", 6)).forEach(outline::addLast)
        intro.openNode()
        late.openNode()
        outline.openNode()
    }
    // Selection and links.

    /**
     * Three pages of "Page N" (Helvetica 24 at [TestPdfs.TEXT_X], [TestPdfs.TEXT_BASELINE]), for select-all and copy
     * across pages.
     */
    val threePages: Path by lazy { TestPdfs.build("three-pages.pdf") { TestPdfs.numberedPages(it, 3) } }

    const val SELECTION_LINK_URI = "https://example.org/p4"

    /** The rect, as a page rect (points from the top-left corner), of link [index] of [selectionLinks]. */
    fun selectionLinkRect(index: Int) = PageRect(TestPdfs.TEXT_X.toDouble(), 272.0 + index * 60.0, 200.0, 20.0)

    /** [threePages] with a link to [SELECTION_LINK_URI] and below it a link to page 3 on the first page, off the text. */
    val selectionLinks: Path by lazy {
        TestPdfs.build("selection-links.pdf") { document ->
            TestPdfs.numberedPages(document, 3)
            val first = document.getPage(0)
            val rects =
                listOf(0, 1).map { selectionLinkRect(it) }.map {
                    PDRectangle(
                        it.x.toFloat(),
                        792f - (it.y + it.height).toFloat(),
                        it.width.toFloat(),
                        it.height.toFloat(),
                    )
                }
            first.annotations.add(TestPdfs.uriLink(rects[0], SELECTION_LINK_URI))
            first.annotations.add(
                PDAnnotationLink().also {
                    it.rectangle = rects[1]
                    it.action =
                        TestPdfs.goTo(TestPdfs.fit(document.getPage(2)))
                },
            )
        }
    }

    const val PAGE_TWO_LINK_URI = "https://example.org/p4-page-two"

    /** The rect, as a page rect, of the link on the second page of [pageTwoLinks]. */
    val PAGE_TWO_LINK_RECT = PageRect(TestPdfs.TEXT_X.toDouble(), 272.0, 200.0, 20.0)

    /** Page 1 has "Short" and no link; page 2 has a longer line of words and a web link below it. */
    val pageTwoLinks: Path by lazy {
        TestPdfs.build("page-two-links.pdf") { document ->
            val font = TestPdfs.liberation(document)
            PDPageContentStream(
                document,
                TestPdfs.page(document),
            ).use { it.lines(font, TestPdfs.TEXT_SIZE, TestPdfs.TEXT_X, TestPdfs.TEXT_BASELINE, "Short") }
            val second = TestPdfs.page(document)
            PDPageContentStream(document, second).use {
                it.lines(
                    font,
                    TestPdfs.TEXT_SIZE,
                    TestPdfs.TEXT_X,
                    TestPdfs.TEXT_BASELINE,
                    "A much longer line of words here",
                )
            }
            val rect = PAGE_TWO_LINK_RECT
            val user =
                PDRectangle(
                    rect.x.toFloat(),
                    792f - (rect.y + rect.height).toFloat(),
                    rect.width.toFloat(),
                    rect.height.toFloat(),
                )
            second.annotations.add(TestPdfs.uriLink(user, PAGE_TWO_LINK_URI))
        }
    }

    // Search: five letter pages with a word on some of them.

    /** The word of [search] and [searchMoved]. */
    const val SEARCH_WORD = "lantern"

    /** [SEARCH_WORD] once on pages 1 and 5, twice on page 3 (on two lines); page 4 has no text. */
    val search: Path by lazy {
        TestPdfs.build("search.pdf") {
            searchPages(
                it,
                listOf("A lantern on page one"),
                listOf("Nothing on page two"),
                listOf("The lantern is lit,", "and a second lantern too"),
                emptyList(),
                listOf("Last lantern"),
            )
        }
    }

    /** [search] with [SEARCH_WORD] on pages 2 and 4 only, once each; the same page sizes. */
    val searchMoved: Path by lazy {
        TestPdfs.build("search-moved.pdf") {
            searchPages(
                it,
                listOf("Nothing on page one"),
                listOf("A lantern on page two"),
                listOf("Nothing on page three"),
                listOf("Another lantern on page four"),
                listOf("Nothing on page five"),
            )
        }
    }

    private fun searchPages(
        document: PDDocument,
        vararg pages: List<String>,
    ) {
        val font = TestPdfs.liberation(document)
        for (lines in pages) {
            PDPageContentStream(document, TestPdfs.page(document)).use {
                if (lines.isNotEmpty()) {
                    it.lines(
                        font,
                        TestPdfs.TEXT_SIZE,
                        TestPdfs.TEXT_X,
                        TestPdfs.TEXT_BASELINE,
                        *lines.toTypedArray(),
                    )
                }
            }
        }
    }
}
