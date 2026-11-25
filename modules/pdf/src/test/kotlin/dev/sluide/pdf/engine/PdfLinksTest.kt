package dev.sluide.pdf.engine

import dev.sluide.pdf.HostilePdfs
import dev.sluide.pdf.TestPdfs
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfLinksTest {
    private fun links(
        file: Path,
        page: Int = 0,
    ): List<PageLink> = (PdfDocument.open(file) as PdfOpenResult.Opened).document.use { it.links(page) }

    /** [TestPdfs.linkRect] on the unrotated letter page (792 high), as displayed. */
    private fun rect(index: Int) = PageRect(68.0, 792.0 - (740.0 - index * 30) - 20, 220.0, 20.0)

    @Test
    fun `web links keep only http, https and mailto in any case, page links resolve like the outline`() {
        val uris = TestPdfs.LINK_URIS.size
        val expected =
            listOf(
                PageLink(rect(0), LinkTarget.Web(URI("https://example.org/a"))),
                PageLink(rect(1), LinkTarget.Web(URI("mailto:someone@example.org"))),
                PageLink(rect(2), LinkTarget.Web(URI("HTTP://EXAMPLE.ORG/upper"))),
                PageLink(rect(uris), LinkTarget.Page(PageTarget(2))),
                PageLink(rect(uris + 1), LinkTarget.Page(PageTarget(1))),
                PageLink(rect(uris + 2), LinkTarget.Page(PageTarget(2))),
                PageLink(rect(uris + 8), LinkTarget.Page(PageTarget(1))),
            )
        assertEquals(expected, links(TestPdfs.links))
    }

    @Test
    fun `a link's rectangle on a page with a crop box is relative to it`() {
        // Drawn at x 110 to 210, y 475 to 495; the crop box starts at x 100 and ends at y 500.
        val expected = PageLink(PageRect(10.0, 5.0, 100.0, 20.0), LinkTarget.Web(URI("https://example.org/crop")))
        assertEquals(listOf(expected), links(TestPdfs.cropBox))
    }

    @Test
    fun `a link whose page is in no page tree is dropped, even when its parents loop`() {
        assertEquals(
            emptyList(),
            assertTimeoutPreemptively<List<PageLink>>(Duration.ofSeconds(20)) {
                links(HostilePdfs.parentLoop)
            },
        )
    }

    @Test
    fun `a link without a rectangle is dropped, the others stay`() {
        val expected = PageLink(PageRect(10.0, 772.0, 10.0, 10.0), LinkTarget.Web(URI("https://example.org/ok")))
        assertEquals(listOf(expected), links(HostilePdfs.linkWithoutRect))
    }

    @Test
    fun `a link to a name in a looping name tree is dropped`() {
        assertEquals(emptyList(), links(HostilePdfs.nameTreeLoop))
    }

    @Test
    fun `a page without links has none`() {
        assertEquals(emptyList(), links(TestPdfs.links, page = 1))
    }

    @ParameterizedTest
    @CsvSource("0, 72, 72, 200, 30", "90, 690, 72, 30, 200", "180, 340, 690, 200, 30", "270, 72, 340, 30, 200")
    fun `a link's rectangle is where the page shows it`(
        rotation: Int,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) {
        val file =
            mapOf(0 to TestPdfs.text, 90 to TestPdfs.rotated90, 180 to TestPdfs.rotated180, 270 to TestPdfs.rotated270)
                .getValue(rotation)
        val expected = PageLink(PageRect(x, y, width, height), LinkTarget.Web(URI("https://example.org/rot$rotation")))
        assertEquals(listOf(expected), links(file))
    }
}
