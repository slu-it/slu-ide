package dev.sluide.previews.plantuml

import dev.sluide.previews.markdown.MarkdownRenderer
import org.jsoup.nodes.Element

/**
 * The page of a PlantUML file ([PlantUmlPreview]): `preview.css` and `preview.js` like a Mermaid file's page. On the
 * body, `plantuml-file` and `zoomable` give the stack of diagrams ([PlantUmlHtml.diagrams]) the zoom as one
 * (`sluide.setZoom`, [dev.sluide.previews.preview.ZoomControls]): fitted to the view as a whole or at a fixed scale, a
 * click on a diagram switching between fitted and 100%; the page scrolls, not the stack.
 */
internal object PlantUmlPage {
    /** The page with [content] (none: an empty page) in the light or [dark] colours. */
    fun render(
        content: Element?,
        dark: Boolean,
    ): String {
        val body = Element("body").addClass("plantuml-file").addClass("zoomable")
        if (content != null) body.appendChild(content)
        return MarkdownRenderer.page(body, dark)
    }
}
