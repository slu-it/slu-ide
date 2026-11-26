package dev.sluide.previews.plantuml

import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import org.jsoup.nodes.Element
import java.util.Base64

/**
 * The HTML of PlantUML diagrams, the same on every page that shows them (a `.puml` file's preview, Markdown fences);
 * its look is `preview.css`'s PlantUML section.
 *
 * Each drawn diagram is two `<img>`s with the SVG in a base64 `data:` URI, so nothing in an SVG runs or links, and no
 * SVG text can leave its attribute: `plantuml-light` and `plantuml-dark`, of which `body.dark` shows only the dark
 * one, so a theme change needs no new drawing. A failed diagram is an error box in its place, text only; with a line
 * it is a syntax error. Element classes only: the page's body decides whether the diagrams zoom (`plantuml-file`).
 */
internal object PlantUmlHtml {
    /** The note above diagrams that Smetana laid out. */
    const val BUILT_IN_LAYOUT = "Graphviz not found, using the built-in layout"

    const val DRAWING = "Drawing the diagram…"

    const val NO_BLOCK = "No @start…/@end… block in this file."

    const val NO_BLOCK_IN_FENCE = "No @start…/@end… block in this fence."

    /**
     * The diagrams of [rendering] in a `div.plantuml`: the note about the built-in layout if PlantUML used it, then the
     * stack, `div.plantuml-diagrams`, with one child per diagram in order: a `div.plantuml-diagram` with both variants,
     * or a `pre.plantuml-error` ("Line N: message" and the class `syntax` if it has a line; [line] names the line
     * otherwise, "Cell 2, line" in a notebook). N is the diagram's line plus [offset]: a Markdown fence is drawn from its
     * first line on, numbered 1, so that its drawing does not depend on where it is.
     */
    fun diagrams(
        rendering: Rendering,
        line: String = "Line",
        offset: Int = 0,
    ): Element {
        val element = Element("div").addClass("plantuml")
        if (rendering.builtInLayout) element.appendChild(note(BUILT_IN_LAYOUT))
        val stack = element.appendElement("div").addClass("plantuml-diagrams")
        for (diagram in rendering.diagrams) {
            when (diagram) {
                is Diagram.Drawn -> {
                    val drawn = stack.appendElement("div").addClass("plantuml-diagram")
                    image(drawn, "plantuml-light", diagram.lightSvg)
                    image(drawn, "plantuml-dark", diagram.darkSvg)
                }

                is Diagram.Failed -> {
                    val error = stack.appendElement("pre").addClass("plantuml-error")
                    val at = diagram.line
                    if (at == null) {
                        error.text(diagram.message)
                    } else {
                        error.addClass("syntax").text("$line ${at + offset}: ${diagram.message}")
                    }
                }
            }
        }
        return element
    }

    /** What a page says while its diagram is drawn for the first time. */
    fun drawing(): Element = note(DRAWING)

    /**
     * What a `.puml` file's page says when the file has text but no `@start…` line, or a Markdown page [inFence] whose
     * fence PlantUML found no block in (a `@start…` line without its `@end…`).
     */
    fun noBlock(inFence: Boolean = false): Element = note(if (inFence) NO_BLOCK_IN_FENCE else NO_BLOCK)

    private fun note(text: String): Element = Element("p").addClass("diagram-note").text(text)

    private fun image(
        parent: Element,
        variant: String,
        svg: String,
    ) {
        val uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        parent
            .appendElement("img")
            .addClass(variant)
            .attr("alt", "")
            .attr("src", uri)
    }
}
