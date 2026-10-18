package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.preview.Preview
import dev.sluide.fileviewers.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows Markdown documents (`.md`, `.markdown`) as highlighted source beside a rendered preview (ADR 0016); see
 * [MarkdownRenderer].
 */
@Component
class MarkdownFileViewer :
    PreviewFileViewer(setOf("md", "markdown"), RSyntaxHighlighter.markdown, MarkdownPreview, CommentSyntax.XML)

/** Markdown rendered by [MarkdownRenderer], scrolling with the source. */
internal object MarkdownPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(MarkdownRenderer.render(text, file.parent, dark))

    override val syncsScroll get() = true

    override val hasOutline get() = true

    override fun outline(text: String) = MarkdownOutline.headings(text)

    override val exports by lazy { listOf(MarkdownHtmlExport()) }
}
