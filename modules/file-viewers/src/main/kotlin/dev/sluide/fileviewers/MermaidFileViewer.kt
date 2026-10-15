package dev.sluide.fileviewers

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
 * `mermaid-file` makes the diagram fit the page (never larger than its actual size) and a click on it switch to its
 * actual size and back.
 */
internal object MermaidPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(render(text, dark))

    /** The page of [source]; the source is only ever text and an attribute value, never HTML. */
    fun render(
        source: String,
        dark: Boolean,
    ): String {
        val body = Element("body").addClass("mermaid-file")
        body
            .appendElement("pre")
            .addClass("mermaid")
            .attr("data-source", source)
            .attr("data-errors", "")
            .text(source)
        return MarkdownRenderer.page(body, dark)
    }
}
