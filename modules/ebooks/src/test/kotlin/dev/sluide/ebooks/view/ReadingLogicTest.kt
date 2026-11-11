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
        val child = TocItem("Section", 150, emptyList(), 0)
        val second = TocItem("Second", 400, emptyList(), 1)
        val first = TocItem("First", 100, listOf(child), 0)
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

    // As the real builder makes them: chapters without text (cover, plates) are empty, and a chapter's start is where
    // the text is when it begins, so a cover shares 0 with the first chapter and plates in a row share an offset.
    private val withPlates =
        listOf(
            ChapterSpan(0, "Cover", 0, 0),
            ChapterSpan(1, "One", 0, 400),
            ChapterSpan(2, "Plate A", 400, 400),
            ChapterSpan(3, "Plate B", 400, 400),
            ChapterSpan(4, "Two", 402, 1000),
        )

    @Test
    fun `progress and the contents entry name the shown chapter when chapters share an offset`() {
        assertEquals("Plate A · 40 %", progressText(withPlates, 1000, 400, 2))
        assertEquals("Plate B · 40 %", progressText(withPlates, 1000, 400, 3))
        assertEquals("Plate B · 40 %", progressText(withPlates, 1000, 400))
        val items = withPlates.map { TocItem(it.title, it.start, emptyList(), it.index) }
        assertEquals("Plate A", contentsItemAt(items, 400, 2)?.title)
        assertEquals("Plate B", contentsItemAt(items, 400, 3)?.title)
        assertEquals("Plate B", contentsItemAt(items, 400)?.title)
        // A chapter that has no entry of its own does not hide the entries before it.
        assertEquals("Plate B", contentsItemAt(items.filter { it.chapter != 4 }, 700, 4)?.title)
    }

    @Test
    fun `an offset alone picks the last of the chapters that start there`() {
        assertEquals(1, chapterAt(withPlates, 0))
        assertEquals(3, chapterAt(withPlates, 400))
        assertEquals(4, chapterAt(withPlates, 402))
    }

    @Test
    fun `a named chapter wins when the offset belongs to it, else the offset decides`() {
        assertEquals(0, chapterFor(withPlates, 0, 0))
        assertEquals(1, chapterFor(withPlates, 0, 1))
        assertEquals(2, chapterFor(withPlates, 400, 2))
        assertEquals(3, chapterFor(withPlates, 400, 3))
        assertEquals(4, chapterFor(withPlates, 402, 4))
        // A stale or foreign name (the file changed) is not believed.
        assertEquals(1, chapterFor(withPlates, 100, 2))
        assertEquals(4, chapterFor(withPlates, 700, 2))
        assertEquals(4, chapterFor(withPlates, 700, 9))
        assertEquals(4, chapterFor(withPlates, 700, null))
        assertEquals(0, chapterFor(emptyList(), 10, 2))
        // A trailing chapter without text: its anchors point at the end of the text.
        val trailing = withPlates + ChapterSpan(5, "Plate C", 1000, 1000)
        assertEquals(5, chapterFor(trailing, 1000, 5))
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
