package dev.sluide.pdf.engine

import dev.sluide.pdf.HostilePdfs
import dev.sluide.pdf.TestPdfs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Path
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfRenderTest {
    private fun opened(file: Path): PdfDocument = (PdfDocument.open(file) as PdfOpenResult.Opened).document

    @Test
    fun `renders a page at the asked scale, 1 being 72 dpi`() {
        opened(TestPdfs.text).use { document ->
            val page = assertNotNull(document.render(0, 2.0))
            assertEquals(1224, page.width)
            assertEquals(1584, page.height)
            assertEquals(page.width * page.height, page.pixels.size)
            assertEquals(2.0, page.scale)
        }
    }

    @Test
    fun `a rotated page renders as displayed`() {
        opened(TestPdfs.rotated90).use { document ->
            val page = assertNotNull(document.render(0, 1.0))
            assertEquals(792 to 612, page.width to page.height)
        }
    }

    @Test
    fun `every pixel is opaque and the text is drawn`() {
        opened(TestPdfs.text).use { document ->
            val page = assertNotNull(document.render(0, 1.0))
            assertTrue(page.pixels.all { it ushr 24 == 0xFF }, "every pixel opaque")
            assertEquals(0xFFFFFFFF.toInt(), page.pixels[0], "white paper")
            assertTrue(page.pixels.any { it and 0xFFFFFF < 0x404040 }, "dark text pixels")
        }
    }

    @Test
    fun `a page larger than the pixel cap renders at the largest scale within it and reports that scale`() {
        opened(TestPdfs.huge).use { document ->
            val page = assertNotNull(document.render(0, 1.0))
            val expected = sqrt(PdfDocument.MAX_PIXELS.toDouble()) / TestPdfs.HUGE_SIDE
            assertEquals(expected, page.scale, 1e-9)
            assertEquals(floor(TestPdfs.HUGE_SIDE * expected).toInt(), page.width)
            assertTrue(page.width.toLong() * page.height <= PdfDocument.MAX_PIXELS)
        }
    }

    @Test
    fun `a page right at the pixel cap keeps its scale`() {
        opened(TestPdfs.huge).use { document ->
            val scale = 4000.0 / TestPdfs.HUGE_SIDE
            val page = assertNotNull(document.render(0, scale))
            assertEquals(scale, page.scale)
            assertEquals(4000 to 4000, page.width to page.height)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a thin page stays within the pixel cap, though each side has at least one pixel`(wide: Boolean) {
        opened(if (wide) HostilePdfs.widePage else HostilePdfs.tallPage).use { document ->
            val page = assertNotNull(document.render(0, 100.0))
            assertEquals(1, if (wide) page.height else page.width)
            assertEquals(PdfDocument.MAX_PIXELS / 1_000_000.0, page.scale)
            assertTrue(page.width.toLong() * page.height <= PdfDocument.MAX_PIXELS, "${page.width} x ${page.height}")
        }
    }

    @Test
    fun `a page whose pixels, each side rounded down, are the cap exactly keeps its scale`() {
        opened(HostilePdfs.atCapPage).use { document ->
            val page = assertNotNull(document.render(0, 1.0))
            assertEquals(1.0, page.scale)
            assertEquals(4000 to 4000, page.width to page.height)
        }
    }

    @Test
    fun `a page without height renders one pixel high within the pixel cap`() {
        opened(HostilePdfs.zeroHeightPage).use { document ->
            assertEquals(listOf(PageSize(5000.0, 0.0)), document.pageSizes)
            val page = assertNotNull(document.render(0, 4000.0))
            assertEquals(PdfDocument.MAX_PIXELS / 5000.0, page.scale)
            assertEquals(1, page.height)
            assertEquals(PdfDocument.MAX_PIXELS, page.width.toLong())
        }
    }

    @Test
    fun `a page whose content stream is corrupt or not a stream renders blank`() {
        opened(HostilePdfs.brokenPages).use { document ->
            for (index in 0..1) {
                val page = assertNotNull(document.render(index, 0.5))
                assertTrue(page.pixels.all { it == 0xFFFFFFFF.toInt() }, "page $index blank")
            }
        }
    }

    @Test
    fun `a page that PDFBox cannot draw throws an IOException`() {
        opened(HostilePdfs.brokenPages).use { document -> assertFailsWith<IOException> { document.render(2, 1.0) } }
    }

    @Test
    fun `a runtime failure while drawing becomes an IOException naming the page`() {
        opened(TestPdfs.text).use { document ->
            val error =
                assertFailsWith<IOException> { document.render(0, 1.0) { throw IllegalStateException("broken") } }
            assertEquals("Could not read page 1: broken", error.message)
        }
    }

    @ParameterizedTest
    @EnumSource(HostilePdfs.PageTreeFlaw::class)
    fun `with a lying page tree each page renders as its size and its content say`(flaw: HostilePdfs.PageTreeFlaw) {
        opened(HostilePdfs.pageTree(flaw)).use { document ->
            assertEquals(HostilePdfs.PAGE_TREE_SIZES.size, document.pageCount)
            for (index in 0 until document.pageCount) {
                val size = HostilePdfs.PAGE_TREE_SIZES[index]
                assertEquals(PageSize(size.width.toDouble(), size.height.toDouble()), document.pageSizes[index])
                val page = assertNotNull(document.render(index, 1.0))
                assertEquals(
                    floor(size.width.toDouble()).toInt() to floor(size.height.toDouble()).toInt(),
                    page.width to page.height,
                )
                val marks =
                    HostilePdfs.PAGE_TREE_SIZES.indices.map { mark ->
                        val (x, y) = HostilePdfs.pageMark(mark)
                        page.pixels[(page.height - y.toInt()) * page.width + x.toInt()] and 0xFF < 0x80
                    }
                assertEquals(List(marks.size) { it == index }, marks, "marks drawn on page $index")
            }
        }
    }

    @Test
    fun `a render cancelled before it starts returns null`() {
        opened(TestPdfs.text).use { assertNull(it.render(0, 1.0) { true }) }
    }

    @Test
    fun `a page without content honours a cancel too`() {
        opened(TestPdfs.blank).use { assertNull(it.render(0, 1.0) { true }) }
    }

    @Test
    fun `cancelling is checked while the page's content is drawn`() {
        opened(TestPdfs.text).use { document ->
            var checks = 0
            assertNull(document.render(0, 1.0) { ++checks > 3 })
            assertEquals(4, checks)
        }
    }

    @Test
    fun `the document renders as before after a cancelled render`() {
        opened(TestPdfs.text).use { document ->
            val before = assertNotNull(document.render(0, 1.0))
            var checks = 0
            assertNull(document.render(0, 1.0) { ++checks > 3 })
            val after = assertNotNull(document.render(0, 1.0))
            assertContentEquals(before.pixels, after.pixels)
        }
    }
}
