package dev.sluide.pdf.engine

import dev.sluide.pdf.HostilePdfs
import dev.sluide.pdf.TestPdfs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfTextTest {
    private fun opened(file: Path): PdfDocument = (PdfDocument.open(file) as PdfOpenResult.Opened).document

    private fun <T> onPage(
        file: Path,
        work: (PdfDocument) -> T,
    ): T = opened(file).use(work)

    /** The bounds of the boxes of [word] (its first occurrence) in [pageText]. */
    private fun wordBox(
        pageText: PageText,
        word: String,
    ): PageRect {
        val start = pageText.text.indexOf(word)
        assertTrue(start >= 0, "'$word' in '${pageText.text}'")
        val boxes =
            (start until start + word.length).map {
                assertNotNull(
                    pageText.boxes[it],
                    "box of '${pageText.text[it]}'",
                )
            }
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        return PageRect(left, top, boxes.maxOf { it.x + it.width } - left, boxes.maxOf { it.y + it.height } - top)
    }

    private fun assertRect(
        expected: PageRect,
        actual: PageRect,
    ) {
        val close =
            listOf(
                expected.x to actual.x,
                expected.y to actual.y,
                expected.width to actual.width,
                expected.height to actual.height,
            ).all { (a, b) -> kotlin.math.abs(a - b) <= 1.0 }
        assertTrue(close, "expected $expected within 1 pt, was $actual")
    }

    private fun assertNear(
        expected: Double,
        actual: Double,
        what: String,
    ) = assertTrue(kotlin.math.abs(expected - actual) <= 1.0, "$what: expected $expected within 1 pt, was $actual")

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `the page text is the plain text, with a box entry per char`(file: Path) {
        onPage(file) { document ->
            for (page in 0 until document.pageCount) {
                val pageText = document.pageText(page)
                assertTrue(pageText.text.isNotBlank(), "text of page $page")
                assertEquals(document.plainText(page), pageText.text)
                assertEquals(pageText.text.length, pageText.boxes.size)
            }
        }
    }

    @Test
    fun `the text is that of the asked page`() {
        onPage(TestPdfs.outline) { document -> assertEquals("Page 3\n", document.plainText(2)) }
    }

    @Test
    fun `the last page of a long document has its own text`() {
        onPage(TestPdfs.manyPages) { document ->
            assertEquals(TestPdfs.MANY_PAGES, document.pageCount)
            assertTrue(document.plainText(TestPdfs.MANY_PAGES - 1).startsWith("Page ${TestPdfs.MANY_PAGES}\n"))
        }
    }

    @Test
    fun `a word's boxes lie where it was drawn`() {
        val box = onPage(TestPdfs.text) { wordBox(it.pageText(0), "needle") }
        val left = TestPdfs.TEXT_X + TestPdfs.liberationWidth("Alpha ")
        assertNear(left, box.x, "left")
        assertNear(TestPdfs.liberationWidth("needle"), box.width, "width")
        val baseline = 792.0 - TestPdfs.TEXT_BASELINE
        assertNear(baseline, box.y + box.height, "baseline")
        assertTrue(box.height in TestPdfs.TEXT_SIZE * 0.5..TestPdfs.TEXT_SIZE * 1.2, "height ${box.height}")
    }

    @ParameterizedTest
    @ValueSource(ints = [90, 180, 270])
    fun `on a rotated page a word's boxes turn with the page`(rotation: Int) {
        val upright = onPage(TestPdfs.text) { wordBox(it.pageText(0), "needle") }
        val file =
            mapOf(
                90 to TestPdfs.rotated90,
                180 to TestPdfs.rotated180,
                270 to TestPdfs.rotated270,
            ).getValue(rotation)
        val box = onPage(file) { wordBox(it.pageText(0), "needle") }
        // The letter page is 612 wide and 792 high before it is turned clockwise.
        val expected =
            when (rotation) {
                90 -> {
                    PageRect(792 - upright.y - upright.height, upright.x, upright.height, upright.width)
                }

                180 -> {
                    PageRect(
                        612 - upright.x - upright.width,
                        792 - upright.y - upright.height,
                        upright.width,
                        upright.height,
                    )
                }

                else -> {
                    PageRect(upright.y, 612 - upright.x - upright.width, upright.height, upright.width)
                }
            }
        assertRect(expected, box)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 90, 180, 270])
    fun `a word's box covers its drawn pixels`(rotation: Int) {
        val file =
            mapOf(0 to TestPdfs.text, 90 to TestPdfs.rotated90, 180 to TestPdfs.rotated180, 270 to TestPdfs.rotated270)
                .getValue(rotation)
        onPage(file) { document ->
            val box = wordBox(document.pageText(0), "needle")
            val image = assertNotNull(document.render(0, 1.0))

            fun dark(
                x: Int,
                y: Int,
            ) = image.pixels[y * image.width + x] and 0xFF < 0x80
            val inside =
                (box.y.toInt() until (box.y + box.height).toInt()).sumOf { y ->
                    (
                        box.x.toInt() until
                            (box.x + box.width).toInt()
                    ).count { x -> dark(x, y) }
                }
            assertTrue(inside > 50, "dark pixels in $box: $inside")
            // The same box moved by three line heights away from the second line is blank paper.
            val (shiftX, shiftY) =
                when (rotation) {
                    0 -> 0 to -3 * box.height.toInt()
                    180 -> 0 to 3 * box.height.toInt()
                    90 -> 3 * box.width.toInt() to 0
                    else -> -3 * box.width.toInt() to 0
                }
            val beside =
                (box.y.toInt() until (box.y + box.height).toInt()).sumOf { y ->
                    (
                        box.x.toInt() until
                            (box.x + box.width).toInt()
                    ).count { x -> dark(x + shiftX, y + shiftY) }
                }
            assertEquals(0, beside, "dark pixels beside $box")
        }
    }

    @Test
    fun `boxes on a page with a crop box are relative to it`() {
        val box = onPage(TestPdfs.cropBox) { wordBox(it.pageText(0), "needle") }
        // Drawn at x 110, baseline 480; the crop box starts at x 100 and ends at y 500.
        assertNear(10.0, box.x, "left")
        assertNear(20.0, box.y + box.height, "baseline")
    }

    @Test
    fun `glyphs entirely outside the crop box are dropped, a glyph partly inside is kept`() {
        val pageText = onPage(TestPdfs.cropBox) { it.pageText(0) }
        // "Outside cropbox" lies above the crop box; "Straddling" runs past its right edge after "Stra".
        assertEquals("needle in cropbox\nStra\n", pageText.text)
        val a = assertNotNull(pageText.boxes[pageText.text.indexOf("Stra") + 3])
        assertTrue(a.x < 300 && a.x + a.width > 300, "'a' crosses the right edge: $a")
    }

    @Test
    fun `a glyph whose box only touches the crop box from outside is dropped, one across its edge is kept`() {
        val letters = onPage(TestPdfs.cropEdges) { it.plainText(0) }.filter(Char::isLetter)
        assertEquals("KMS", letters.toList().sorted().joinToString(""))
    }

    @Test
    fun `line ends the extraction inserted have no box`() {
        val pageText = onPage(TestPdfs.text) { it.pageText(0) }
        val lineEnds = pageText.text.indices.filter { pageText.text[it] == '\n' }
        assertEquals(2, lineEnds.size, pageText.text)
        lineEnds.forEach { assertNull(pageText.boxes[it]) }
        assertNotNull(pageText.boxes[pageText.text.indexOf(' ')], "a space drawn on the page has its box")
    }

    @Test
    fun `a word gap the extraction inserted has no box`() {
        val pageText = onPage(TestPdfs.std14) { it.pageText(0) }
        val gap = pageText.text.indexOf("LeftColumn ") + "LeftColumn".length
        assertTrue(gap >= "LeftColumn".length, pageText.text)
        assertNull(pageText.boxes[gap])
        assertNotNull(pageText.boxes[gap + 1])
    }

    @Test
    fun `a ligature glyph gives each of its chars a share of its box`() {
        val pageText = onPage(TestPdfs.ligature) { it.pageText(0) }
        assertEquals("find it\n", pageText.text)
        val f = assertNotNull(pageText.boxes[0])
        val i = assertNotNull(pageText.boxes[1])
        val n = assertNotNull(pageText.boxes[2])
        assertNear(TestPdfs.TEXT_X.toDouble(), f.x, "f left")
        assertNear(f.x + f.width, i.x, "i left")
        assertNear(f.width, i.width, "share")
        assertNear(TestPdfs.liberationWidth("\uFB01"), f.width + i.width, "glyph width")
        assertNear(i.x + i.width, n.x, "n left")
        assertEquals(f.y, i.y)
    }

    @Test
    fun `text drawn at an angle gets its boxes where it was drawn`() {
        val pageText = onPage(TestPdfs.directions) { it.pageText(0) }
        val height = 792.0
        for ((word, angle, start) in TestPdfs.DIRECTION_WORDS) {
            val box = wordBox(pageText, word)
            val length = TestPdfs.liberationWidth(word)
            val (x, y) = start.first.toDouble() to height - start.second
            when (angle) {
                0 -> {
                    assertNear(x, box.x, "$word left")
                    assertNear(length, box.width, "$word width")
                    assertNear(y, box.y + box.height, "$word baseline")
                }

                90 -> {
                    assertNear(x, box.x + box.width, "$word baseline")
                    assertNear(y - length, box.y, "$word top")
                    assertNear(length, box.height, "$word length")
                }

                180 -> {
                    assertNear(x - length, box.x, "$word left")
                    assertNear(length, box.width, "$word width")
                    assertNear(y, box.y, "$word baseline")
                }

                else -> {
                    assertNear(x, box.x, "$word baseline")
                    assertNear(y, box.y, "$word top")
                    assertNear(length, box.height, "$word length")
                }
            }
        }
    }

    @Test
    fun `a page whose content stream is corrupt has only its page end, one without a stream nothing`() {
        onPage(HostilePdfs.brokenPages) { document ->
            assertEquals("\n", document.pageText(0).text)
            assertEquals("", document.pageText(1).text)
        }
    }

    @Test
    fun `a page the document does not have throws an IOException from every page method`() {
        onPage(TestPdfs.text) { document ->
            assertFailsWith<IOException> { document.pageText(1) }
            assertFailsWith<IOException> { document.plainText(1) }
            assertFailsWith<IOException> { document.links(1) }
            assertFailsWith<IOException> { document.render(1, 1.0) }
        }
    }

    @Test
    fun `a page tree whose kids loop is read without PDFBox's complaint about it`() {
        val logger =
            java.util.logging.Logger
                .getLogger("org.apache.pdfbox.pdmodel.PDPageTree")
        val records = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handler =
            object : java.util.logging.Handler() {
                override fun publish(record: java.util.logging.LogRecord) {
                    records += record.message
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        try {
            onPage(HostilePdfs.pageTree(HostilePdfs.PageTreeFlaw.KIDS_LOOP)) { document ->
                (0 until document.pageCount).forEach { document.plainText(it) }
            }
        } finally {
            logger.removeHandler(handler)
        }
        assertEquals(emptyList(), records)
    }

    @Test
    fun `the text of a PDF with only an owner password is not restricted`() {
        assertTrue("needle" in onPage(TestPdfs.ownerOnly) { it.plainText(0) })
    }

    companion object {
        @JvmStatic
        fun fixtures() = TestPdfs.all
    }
}
