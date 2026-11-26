package dev.sluide.previews.plantuml

import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTML of PlantUML diagrams, shared by the file preview and Markdown pages. */
class PlantUmlHtmlTest {
    /** [element] as a page would carry it: written out and parsed again. */
    private fun reparsed(element: Element): Element = Jsoup.parseBodyFragment(element.outerHtml()).body()

    private fun svgOf(image: Element): String {
        val src = image.attr("src")
        val prefix = "data:image/svg+xml;base64,"
        assertTrue(src.startsWith(prefix), src)
        return String(Base64.getDecoder().decode(src.removePrefix(prefix)), Charsets.UTF_8)
    }

    @Test
    fun `each diagram shows both variants, in order, as SVG data URIs`() {
        val rendering =
            Rendering(
                listOf(
                    Diagram.Drawn("<svg>one ü</svg>", "<svg>one dark</svg>"),
                    Diagram.Drawn("<svg>two</svg>", "<svg>two dark</svg>"),
                ),
                false,
            )

        val html = reparsed(PlantUmlHtml.diagrams(rendering))

        val diagrams = html.select("div.plantuml > div.plantuml-diagrams > *")
        assertEquals(2, diagrams.size)
        val svgs =
            diagrams.map { diagram ->
                assertTrue(diagram.hasClass("plantuml-diagram"), diagram.outerHtml())
                diagram.children().map { it.normalName() + "." + it.className() + " " + svgOf(it) }
            }
        assertEquals(
            listOf(
                listOf("img.plantuml-light <svg>one ü</svg>", "img.plantuml-dark <svg>one dark</svg>"),
                listOf("img.plantuml-light <svg>two</svg>", "img.plantuml-dark <svg>two dark</svg>"),
            ),
            svgs,
        )
        assertTrue(html.select(".diagram-note").isEmpty(), "no note with Graphviz")
    }

    @Test
    fun `a failed diagram is an error box in its place, a syntax error with its line`() {
        val rendering =
            Rendering(
                listOf(
                    Diagram.Failed("Syntax Error?", 7),
                    Diagram.Drawn("<svg>drawn</svg>", "<svg>dark</svg>"),
                    Diagram.Failed("Drawing took longer than 20 s", null),
                ),
                false,
            )

        val children = reparsed(PlantUmlHtml.diagrams(rendering)).select("div.plantuml > div.plantuml-diagrams > *")

        assertEquals(listOf("pre", "div", "pre"), children.map { it.normalName() })
        assertEquals(setOf("plantuml-error", "syntax"), children[0].classNames())
        assertEquals("Line 7: Syntax Error?", children[0].wholeText())
        assertEquals(setOf("plantuml-error"), children[2].classNames(), "no syntax error without a line")
        assertEquals("Drawing took longer than 20 s", children[2].wholeText())
    }

    @Test
    fun `diagrams that needed the built-in layout get a note above them`() {
        val rendering = Rendering(listOf(Diagram.Drawn("<svg/>", "<svg/>")), true)

        val parts = reparsed(PlantUmlHtml.diagrams(rendering)).select("div.plantuml > *")

        assertEquals(
            listOf("p.diagram-note", "div.plantuml-diagrams"),
            parts.map {
                it.normalName() + "." +
                    it.className()
            },
        )
        assertEquals(PlantUmlHtml.BUILT_IN_LAYOUT, parts[0].text())
    }

    @Test
    fun `while drawing and without a block a note says so`() {
        for ((element, text) in listOf(
            PlantUmlHtml.drawing() to PlantUmlHtml.DRAWING,
            PlantUmlHtml.noBlock() to PlantUmlHtml.NO_BLOCK,
        )) {
            val html = reparsed(element)
            assertEquals(1, html.childrenSize(), html.html())
            assertEquals("p", html.child(0).normalName())
            assertEquals(setOf("diagram-note"), html.child(0).classNames())
            assertEquals(text, html.child(0).text())
        }
    }

    @Test
    fun `hostile messages stay text and hostile SVGs stay inside their data URI`() {
        val message = "</pre><script>alert(\"x\")</script><img src='http://example.com/x' onerror=\"alert(1)\">&amp;"
        val svg = "<svg>\"><script>alert(1)</script><img src=\"http://example.com/y\"></svg>"
        val rendering = Rendering(listOf(Diagram.Failed(message, 2), Diagram.Drawn(svg, svg)), false)

        val html = reparsed(PlantUmlHtml.diagrams(rendering))

        assertTrue(html.select("script").isEmpty(), html.html())
        assertEquals(2, html.select("img").size, html.html())
        assertEquals("Line 2: $message", html.selectFirst("pre.plantuml-error")!!.wholeText())
        for (image in html.select("img")) {
            assertEquals(setOf("src", "class", "alt"), image.attributes().map { it.key }.toSet())
            assertEquals(svg, svgOf(image))
        }
    }
}
