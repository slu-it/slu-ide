package dev.sluide.pdf.engine

import dev.sluide.pdf.TestPdfs
import java.nio.file.Path
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
