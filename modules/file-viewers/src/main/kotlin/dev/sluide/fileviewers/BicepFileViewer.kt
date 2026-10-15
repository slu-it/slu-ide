package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows Bicep files (`.bicep`) as highlighted source ([BicepHighlighter]) and, one click away, the graph of their
 * resources and modules ([BicepPreview]).
 */
@Component
class BicepFileViewer : PreviewFileViewer(setOf("bicep"), BicepHighlighter, BicepPreview, CommentSyntax.SLASHES)

/**
 * The resource graph ([BicepGraph]) drawn by Mermaid like a Mermaid file ([MermaidPreview]): fitted, actual size on a
 * click, drawn again in the other colour scheme. A file without resources or modules shows a note instead.
 */
internal object BicepPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(render(text, dark))

    fun render(
        text: String,
        dark: Boolean,
    ): String {
        val graph =
            try {
                BicepGraph.parse(text)
            } catch (e: RuntimeException) {
                return note("The resource graph could not be read: ${e.message}", dark)
            }
        if (graph.nodes.isEmpty()) return note("No resources or modules in this file.", dark)
        return MermaidPreview.render(graph.toMermaid(), dark, graph.note)
    }

    private fun note(
        text: String,
        dark: Boolean,
    ): String {
        val body = Element("body")
        body.appendElement("p").addClass("diagram-note").text(text)
        return MarkdownRenderer.page(body, dark)
    }

    override val defaultMode get() = PreviewMode.SOURCE
}
