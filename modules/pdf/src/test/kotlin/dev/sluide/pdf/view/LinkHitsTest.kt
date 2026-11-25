package dev.sluide.pdf.view

import dev.sluide.pdf.engine.LinkTarget
import dev.sluide.pdf.engine.PageLink
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageTarget
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class LinkHitsTest {
    private fun link(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        uri: String,
    ) = PageLink(PageRect(x, y, width, height), LinkTarget.Web(URI(uri)))

    private val below = link(10.0, 10.0, 100.0, 50.0, "https://below.example")
    private val above = link(50.0, 20.0, 100.0, 20.0, "https://above.example")

    @Test
    fun `linkAt gives the link whose rect holds the point, edges included`() {
        val links = listOf(below)
        assertEquals(below, linkAt(links, 60.0, 30.0))
        assertEquals(below, linkAt(links, 10.0, 10.0))
        assertEquals(below, linkAt(links, 110.0, 60.0))
        assertNull(linkAt(links, 9.9, 30.0))
        assertNull(linkAt(links, 110.1, 30.0))
        assertNull(linkAt(links, 60.0, 9.9))
        assertNull(linkAt(links, 60.0, 60.1))
        assertNull(linkAt(emptyList(), 60.0, 30.0))
    }

    @Test
    fun `linkAt gives the topmost of overlapping links, the last in the list`() {
        assertEquals(above, linkAt(listOf(below, above), 60.0, 30.0))
        assertEquals(below, linkAt(listOf(above, below), 60.0, 30.0))
        assertEquals(below, linkAt(listOf(below, above), 20.0, 30.0))
    }

    @Test
    fun `label is the address of a web link and the 1-based number of a page`() {
        assertEquals("https://example.org/a?b=c", LinkTarget.Web(URI("https://example.org/a?b=c")).label())
        assertEquals("Page 1", LinkTarget.Page(PageTarget(0)).label())
        assertEquals("Page 8", LinkTarget.Page(PageTarget(7, 100.0)).label())
    }
}
