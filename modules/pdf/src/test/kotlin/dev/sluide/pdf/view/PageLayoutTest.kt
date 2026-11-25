package dev.sluide.pdf.view

import dev.sluide.fileviewers.zoom.ZoomSteps
import dev.sluide.pdf.engine.PageSize
import dev.sluide.pdf.engine.PageTarget
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PageLayoutTest {
    private val sizes = listOf(PageSize(100.0, 200.0), PageSize(300.0, 100.0), PageSize(100.0, 200.0))

    // Scale 2: heights 400, 200, 400; tops 12, 420, 628; content 1040 high; widths 200, 600, 200; content 624 wide.
    private val layout = PageLayout(sizes, 2.0)

    // Scale 1: heights 200, 100, 200; tops 12, 220, 328; content 540 high and 324 wide.
    private val small = PageLayout(sizes, 1.0)

    @Test
    fun `stacks the pages at the scale with a gap between them and margins around`() {
        assertEquals(listOf(12.0, 420.0, 628.0), (0..2).map(layout::top))
        assertEquals(listOf(200.0, 600.0, 200.0), (0..2).map(layout::pageWidth))
        assertEquals(listOf(400.0, 200.0, 400.0), (0..2).map(layout::pageHeight))
        assertEquals(1040.0, layout.height)
        assertEquals(624.0, layout.width)
        assertEquals(3, layout.pageCount)
        assertEquals(2.0, layout.scale)
    }

    @Test
    fun `centres each page in the column, or in the content when the column is narrower`() {
        assertEquals(400.0, layout.left(0, 1000.0))
        assertEquals(200.0, layout.left(1, 1000.0))
        assertEquals(12.0, layout.left(1, 500.0))
        assertEquals(212.0, layout.left(2, 500.0))
    }

    @ParameterizedTest
    @CsvSource("-5, 0", "0, 0", "411.9, 0", "415, 0", "420, 1", "627, 1", "628, 2", "5000, 2")
    fun `the page near a height is the last one starting at or above it`(
        y: Double,
        page: Int,
    ) {
        assertEquals(page, layout.pageNear(y))
    }

    @ParameterizedTest
    @CsvSource("5, ", "12, 0", "411.9, 0", "412, ", "415, ", "420, 1", "619.9, 1", "620, ", "1027, 2", "1028, ")
    fun `the page at a height is none in the gaps and margins`(
        y: Double,
        page: Int?,
    ) {
        assertEquals(page, layout.pageAt(y))
    }

    @ParameterizedTest
    @CsvSource("300, 200, 0", "320, 200, 1", "315, 200, 0", "100, 900, 1", "900, 400, 2")
    fun `the current page is the one under the vertical middle of the viewport, a gap counting to the page above`(
        top: Double,
        height: Double,
        page: Int,
    ) {
        assertEquals(page, layout.currentPage(Viewport(0.0, top, 500.0, height)))
    }

    @ParameterizedTest
    @CsvSource(
        "0, 100, 0, 0",
        "410, 15, 0, 1",
        "412, 10, 1, 1",
        "413, 10, 1, 1",
        "0, 420, 0, 0",
        "0, 421, 0, 1",
        "0, 2000, 0, 2",
        "1000, 100, 2, 2",
    )
    fun `the visible pages are those with a part in the viewport`(
        top: Double,
        height: Double,
        first: Int,
        last: Int,
    ) {
        assertEquals(first..last, layout.visiblePages(Viewport(0.0, top, 500.0, height)))
    }

    @Test
    fun `no page is visible in a gap`() {
        assertEquals(IntRange.EMPTY, layout.visiblePages(Viewport(0.0, 413.0, 500.0, 5.0)))
    }

    @Test
    fun `the top of a target is its page's top, moved down by its height within the page`() {
        assertEquals(420.0, layout.topOf(PageTarget(1)))
        assertEquals(520.0, layout.topOf(PageTarget(1, 50.0)))
    }

    @Test
    fun `a target's page and height are kept within the document`() {
        assertEquals(620.0, layout.topOf(PageTarget(1, 500.0)))
        assertEquals(420.0, layout.topOf(PageTarget(1, -20.0)))
        assertEquals(628.0, layout.topOf(PageTarget(7)))
        assertEquals(12.0, layout.topOf(PageTarget(-1)))
    }

    @Test
    fun `fit page fits the whole page within the margins and the scroll bar`() {
        assertEquals(2.0, PageLayout.fitPage(PageSize(100.0, 200.0), 524.0, 424.0, 0.0))
        assertEquals(2.0, PageLayout.fitPage(PageSize(100.0, 200.0), 524.0, 424.0, 100.0))
        assertEquals(1.8, PageLayout.fitPage(PageSize(100.0, 200.0), 224.0, 2024.0, 20.0), 1e-9)
    }

    @Test
    fun `fit width fits the page's width within the margins and the scroll bar`() {
        assertEquals(1.8, PageLayout.fitWidth(PageSize(100.0, 200.0), 224.0, 20.0), 1e-9)
        assertEquals(5.0, PageLayout.fitWidth(PageSize(100.0, 2000.0), 524.0, 0.0))
    }

    @Test
    fun `a fit is kept between the smallest fit and the largest zoom`() {
        assertEquals(PageLayout.MIN_FIT, PageLayout.fitPage(PageSize(100.0, 200.0), 10.0, 424.0, 0.0))
        assertEquals(PageLayout.MIN_FIT, PageLayout.fitWidth(PageSize(100.0, 200.0), 10.0, 0.0))
        assertEquals(ZoomSteps.MAX, PageLayout.fitPage(PageSize(0.0, 0.0), 524.0, 424.0, 0.0))
        assertEquals(ZoomSteps.MAX, PageLayout.fitWidth(PageSize(0.0, 10.0), 524.0, 0.0))
    }

    @Test
    fun `a scale change keeps the point under the anchor in place`() {
        val view = Viewport(0.0, 200.0, 500.0, 300.0)
        // y 250 is 30 points into page 1, which is at 480 at scale 2; x 300 is 50 points right of the centre.
        assertEquals(Viewport(112.0, 430.0, 500.0, 300.0), layout.keepAnchor(small, view, 300.0, 50.0))
    }

    @Test
    fun `a scale change from another scale than 1 keeps the point under the anchor in place`() {
        // Scale 4: tops 12, 820, 1228; content 2040 high and 1224 wide.
        val big = PageLayout(sizes, 4.0)
        val view = Viewport(112.0, 430.0, 500.0, 300.0)
        // y 480 is 30 points into page 1, at 940 at scale 4; x 412 is 50 points right of the centre, at 812.
        assertEquals(Viewport(512.0, 890.0, 500.0, 300.0), big.keepAnchor(layout, view, 300.0, 50.0))
    }

    @Test
    fun `the content is as wide as the widest page, the first one too`() {
        assertEquals(324.0, PageLayout(listOf(PageSize(300.0, 100.0), PageSize(100.0, 100.0)), 1.0).width)
    }

    @Test
    fun `a scale change keeps the viewport within the content`() {
        val view = Viewport(124.0, 740.0, 500.0, 300.0)
        assertEquals(Viewport(0.0, 240.0, 500.0, 300.0), small.keepAnchor(layout, view, 0.0, 0.0))
        val left = Viewport(0.0, 0.0, 500.0, 300.0)
        assertEquals(Viewport(124.0, 0.0, 500.0, 300.0), layout.keepAnchor(small, left, 450.0, 0.0))
    }

    @ParameterizedTest
    @CsvSource(
        "120, 20, true, 100",
        "100, 20, true, 100",
        "90, 20, true, 90",
        "190, 20, true, 110",
        "500, 20, true, 460",
        "500, 20, false, 420",
        "10, 20, false, 10",
        "10, 20, true, -30",
        "200, 20, true, 160",
        "80, 20, true, 40",
        "50, 300, false, 50",
        "150, 300, false, 150",
    )
    fun `revealing a range scrolls the least, or centres it when it was off-screen and asked to`(
        start: Double,
        size: Double,
        centre: Boolean,
        result: Double,
    ) {
        assertEquals(result, revealed(start, size, 100.0, 100.0, centre))
    }
}
