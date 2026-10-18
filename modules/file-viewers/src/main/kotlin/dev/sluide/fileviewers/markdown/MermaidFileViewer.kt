package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.preview.Preview
import dev.sluide.fileviewers.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Shows Mermaid diagrams (`.mmd`, `.mermaid`) as highlighted source beside the drawn diagram ([MermaidPreview]). */
@Component
class MermaidFileViewer :
    PreviewFileViewer(setOf("mmd", "mermaid"), RSyntaxHighlighter.mermaid, MermaidPreview, CommentSyntax.MERMAID)

/**
 * The file as one diagram, drawn like ` ```mermaid ` fences in Markdown ([MarkdownRenderer.page], `preview.js`), but
 * with `data-errors`: a diagram that does not parse shows Mermaid's error message instead of nothing. On the body,
 * `mermaid-file` and `zoomable` make the page scroll instead of the diagram and give the diagram the zoom
 * (`sluide.setZoom`, [dev.sluide.fileviewers.preview.ZoomControls]): fitted to the view or at a fixed scale, a click
 * switching between fitted and 100%.
 */
internal object MermaidPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(render(text, dark))

    override val zooms get() = true

    /**
     * The page of [source], with [note] (if any) as a line above the diagram; both are only ever text and the source
     * an attribute value, never HTML.
     */
    fun render(
        source: String,
        dark: Boolean,
        note: String? = null,
    ): String {
        val body = Element("body").addClass("mermaid-file").addClass("zoomable")
        if (note != null) body.appendElement("p").addClass("diagram-note").text(note)
        body
            .appendElement("pre")
            .addClass("mermaid")
            .attr("data-source", source)
            .attr("data-errors", "")
            .text(source)
        return MarkdownRenderer.page(body, dark)
    }
}
