package dev.sluide.pdf.view

import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageText
import dev.sluide.pdf.engine.PdfDocument
import dev.sluide.pdf.engine.PdfOpenResult
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextRectsTest {
    private fun box(
        x: Double,
        y: Double,
        width: Double = 10.0,
        height: Double = 10.0,
    ) = PageRect(x, y, width, height)

    /** "ab cd\nef": a and b, a word gap, c and d on the first line; e and f on the second. */
    private val twoLines =
        PageText(
            "ab cd\nef",
            listOf(
                box(0.0, 0.0),
                box(10.0, 0.0),
                null,
                box(25.0, 0.0),
                box(35.0, 0.0),
                null,
                box(0.0, 14.0),
                box(10.0, 14.0),
            ),
        )

    private fun pageText(file: Path): PageText =
        (PdfDocument.open(file) as PdfOpenResult.Opened).document.use { it.pageText(0) }

    private fun PageRect.contains(other: PageRect) =
        other.x >= x - 1e-9 &&
            other.y >= y - 1e-9 &&
            other.x + other.width <= x + width + 1e-9 &&
            other.y + other.height <= y + height + 1e-9

    @Test
    fun `a range on one line gives the union of its boxes, the word gap inside it`() {
        assertEquals(listOf(PageRect(0.0, 0.0, 45.0, 10.0)), textRects(twoLines, 0, 5))
    }

    @Test
    fun `a range over two lines gives one rect per line`() {
        assertEquals(
            listOf(PageRect(0.0, 0.0, 45.0, 10.0), PageRect(0.0, 14.0, 20.0, 10.0)),
            textRects(twoLines, 0, 8),
        )
    }

    @Test
    fun `a range beyond the text is kept within it`() {
        assertEquals(textRects(twoLines, 0, 8), textRects(twoLines, -5, 100))
    }

    @Test
    fun `a range from the middle of a line to the middle of it covers only its chars`() {
        assertEquals(listOf(PageRect(10.0, 0.0, 25.0, 10.0)), textRects(twoLines, 1, 4))
    }

    @Test
    fun `chars far apart without a line end in between are two runs`() {
        val text = PageText("ab", listOf(box(0.0, 0.0), box(50.0, 40.0)))
        assertEquals(listOf(box(0.0, 0.0), box(50.0, 40.0)), textRects(text, 0, 2))
    }

    @Test
    fun `a box beside the previous one on either axis continues the run`() {
        // Below by more than half its height, but sharing all of its width: a line running down.
        val down = PageText("ab", listOf(box(0.0, 0.0), box(0.0, 8.0)))
        assertEquals(listOf(PageRect(0.0, 0.0, 10.0, 18.0)), textRects(down, 0, 2))
        // Overlapping exactly half of the smaller box on the y axis, apart on x.
        val half = PageText("ab", listOf(box(0.0, 0.0), box(30.0, 5.0, height = 20.0)))
        assertEquals(listOf(PageRect(0.0, 0.0, 40.0, 25.0)), textRects(half, 0, 2))
    }

    @Test
    fun `a line end ends the run even where the next box would continue it`() {
        val text = PageText("ab\ncd", listOf(box(0.0, 0.0), box(10.0, 0.0), null, box(20.0, 0.0), box(30.0, 0.0)))
        assertEquals(listOf(PageRect(0.0, 0.0, 20.0, 10.0), PageRect(20.0, 0.0, 20.0, 10.0)), textRects(text, 0, 5))
    }

    @ParameterizedTest
    @CsvSource("8, 0, 0, 30", "0, 0, 8, 30", "30, 8, 0, 0", "0, 0, 30, 8")
    fun `boxes that share less than half of the smaller one on both axes are two runs`(
        firstX: Double,
        firstY: Double,
        secondX: Double,
        secondY: Double,
    ) {
        val text = PageText("ab", listOf(box(firstX, firstY), box(secondX, secondY)))
        assertEquals(listOf(box(firstX, firstY), box(secondX, secondY)), textRects(text, 0, 2))
    }

    @Test
    fun `a run keeps the larger extents of either box`() {
        val wider = PageText("ab", listOf(box(0.0, 0.0, width = 20.0), box(0.0, 8.0)))
        assertEquals(listOf(PageRect(0.0, 0.0, 20.0, 18.0)), textRects(wider, 0, 2))
        val taller = PageText("ab", listOf(box(0.0, 0.0, height = 30.0), box(10.0, 0.0)))
        assertEquals(listOf(PageRect(0.0, 0.0, 20.0, 30.0)), textRects(taller, 0, 2))
        val leftOf = PageText("ab", listOf(box(10.0, 5.0), box(0.0, 0.0)))
        assertEquals(listOf(PageRect(0.0, 0.0, 20.0, 15.0)), textRects(leftOf, 0, 2))
    }

    @Test
    fun `an empty range or one of inserted chars only gives no rect`() {
        assertEquals(emptyList(), textRects(twoLines, 3, 3))
        assertEquals(emptyList(), textRects(twoLines, 2, 3))
        assertEquals(emptyList(), textRects(twoLines, 5, 6))
    }

    @Test
    fun `a word on a page turned by 90 degrees gives a rect taller than wide`() {
        val text = pageText(TestPdfs.rotated90)
        val start = text.text.indexOf("needle")
        val rects = textRects(text, start, start + "needle".length)
        assertEquals(1, rects.size)
        assertTrue(rects.single().height > rects.single().width, rects.toString())
    }

    @Test
    fun `a word of a real page gives one rect around its glyph boxes`() {
        val text = pageText(TestPdfs.text)
        val start = text.text.indexOf("needle")
        val rect = textRects(text, start, start + "needle".length).single()
        (start until start + "needle".length).forEach { assertTrue(rect.contains(checkNotNull(text.boxes[it]))) }
        assertTrue(rect.width > 0 && rect.width < 100, rect.toString())
    }
}
