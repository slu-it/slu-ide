package dev.sluide.previews.plantuml

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The page of a PlantUML file: its content in a body that zooms the stack of diagrams. */
class PlantUmlPageTest {
    @Test
    fun `the content goes into a zoomable PlantUML body with the previews' stylesheet and script only`() {
        val page = Jsoup.parse(PlantUmlPage.render(PlantUmlHtml.noBlock(), dark = true))

        assertEquals(setOf("dark", "plantuml-file", "zoomable"), page.body().classNames())
        assertEquals(PlantUmlHtml.NO_BLOCK, page.body().selectFirst("p.diagram-note")?.text())
        assertTrue(page.select("style").any { ".plantuml-diagrams" in it.data() }, "preview.css with PlantUML's rules")
        val scripts = page.select("script")
        assertEquals(1, scripts.size, "no Mermaid")
        assertTrue("window.sluide" in scripts.single().data())
    }

    @Test
    fun `without content the page is empty`() {
        val page = Jsoup.parse(PlantUmlPage.render(null, dark = false))

        assertEquals(setOf("plantuml-file", "zoomable"), page.body().classNames())
        assertEquals(listOf("script"), page.body().children().map { it.normalName() })
    }
}
