package dev.sluide.ebooks.view

import dev.sluide.ebooks.ChapterSpan
import dev.sluide.ebooks.TocItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReadingLogicTest {
    private val chapters = listOf(ChapterSpan(0, "One", 0, 400), ChapterSpan(1, "Two", 400, 1000))

    @Test
    fun `progress shows the chapter and the percent rounded down`() {
        assertEquals("One · 0 %", progressText(chapters, 1000, 0))
        assertEquals("One · 39 %", progressText(chapters, 1000, 399))
        assertEquals("Two · 40 %", progressText(chapters, 1000, 400))
        assertEquals("Two · 100 %", progressText(chapters, 1000, 5000))
    }

    @Test
    fun `progress without chapters or text is the percent alone`() {
        assertEquals("33 %", progressText(emptyList(), 300, 100))
        assertEquals("0 %", progressText(emptyList(), 0, 0))
    }

    @Test
    fun `contents item is the deepest one at or before the position`() {
        val child = TocItem("Section", 150, emptyList())
        val second = TocItem("Second", 400, emptyList())
        val first = TocItem("First", 100, listOf(child))
        val items = listOf(first, second)
        assertNull(contentsItemAt(items, 50))
        assertSame(first, contentsItemAt(items, 100))
        assertSame(child, contentsItemAt(items, 399))
        assertSame(second, contentsItemAt(items, 400))
    }

    @Test
    fun `chapter at an offset is the last one starting before it`() {
        assertEquals(0, chapterAt(chapters, 0))
        assertEquals(0, chapterAt(chapters, 399))
        assertEquals(1, chapterAt(chapters, 400))
        assertEquals(0, chapterAt(emptyList(), 10))
    }

    @Test
    fun `shown page maps local and whole-book offsets`() {
        val page = ShownPage("", 1, 400)
        assertEquals(450, page.toWhole(50))
        assertEquals(50, page.toLocal(450))
    }

    @Test
    fun `marks are clipped to the chapter of a chapter page and mapped to local offsets`() {
        val page = ShownPage("", 1, 400)
        assertEquals(50 to 150, page.localRange(chapters, 450, 550))
        // Partly in the chapter: only that part.
        assertEquals(0 to 10, page.localRange(chapters, 390, 410))
        assertNull(page.localRange(chapters, 100, 300))
        assertNull(page.localRange(chapters, 1000, 1200))
    }

    @Test
    fun `a whole-book page shows every mark unchanged`() {
        val page = ShownPage("", null, 0)
        assertEquals(390 to 410, page.localRange(chapters, 390, 410))
        assertNull(page.localRange(chapters, 5, 5))
    }

    @Test
    fun `local offset exists only inside the shown chapter`() {
        val page = ShownPage("", 1, 400)
        assertEquals(10, page.localOffset(chapters, 410))
        assertNull(page.localOffset(chapters, 399))
        assertEquals(77, ShownPage("", null, 0).localOffset(chapters, 77))
    }
}
