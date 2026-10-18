package dev.sluide.fileviewers.bicep

import dev.sluide.fileviewers.preview.PreviewMode
import dev.sluide.fileviewers.preview.Vendor
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BicepPreviewTest {
    @Test
    fun `the page draws the resource graph like a Mermaid file, after the source`() {
        val page = Jsoup.parse(BicepPreview.render(MAIN_BICEP, dark = true))
        val diagram = assertNotNull(page.selectFirst("pre.mermaid"))

        assertEquals(BicepGraph.parse(MAIN_BICEP).toMermaid(), diagram.attr("data-source"))
        assertTrue(diagram.hasAttr("data-errors"))
        assertTrue(page.body().hasClass("dark") && page.body().hasClass("mermaid-file"))
        assertNull(page.selectFirst(".diagram-note"))
        assertEquals(PreviewMode.SOURCE, BicepPreview.defaultMode)
    }

    @Test
    fun `a file without resources or modules says so, without Mermaid`() {
        val page = Jsoup.parse(BicepPreview.render("param p string\n// resource x 'A' = {}\n", dark = false))

        assertNull(page.selectFirst("pre.mermaid"))
        assertEquals("No resources or modules in this file.", page.selectFirst("p.diagram-note")?.text())
        assertTrue(page.select("script").none { Vendor.mermaidJs in it.data() }, "no Mermaid without a diagram")
    }

    @Test
    fun `a graph that is cut says so above the diagram`() {
        val many = (0 until 201).joinToString("\n") { "resource r$it 'A' = {}" }
        val page = Jsoup.parse(BicepPreview.render(many, dark = false))

        assertEquals("Showing the first 200 of 201 resources and modules.", page.selectFirst("p.diagram-note")?.text())
        assertEquals(1, page.select("pre.mermaid").size)
    }
}
