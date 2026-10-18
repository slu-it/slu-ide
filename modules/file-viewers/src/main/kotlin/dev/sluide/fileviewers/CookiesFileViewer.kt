package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.preview.Preview
import dev.sluide.fileviewers.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows cookie jars (`.cookies`, IntelliJ's `http-client.cookies`) as text beside a sortable table ([CookiesPreview]).
 */
@Component
class CookiesFileViewer :
    PreviewFileViewer(setOf("cookies"), RSyntaxHighlighter.cookies, CookiesPreview, CommentSyntax.NONE)

/**
 * The cookies as a table ([CsvRenderer]), scrolling with the source: tab-separated, the first line
 * (`# domain path name value date`) as header without its `# `. Quotes are part of the values.
 */
internal object CookiesPreview : Preview {
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
    ): String = CsvRenderer.render(text.replaceFirst(headerMark, ""), '\t', dark, quotes = false)

    /** The `# ` before the header's first column; the line stays, so rows keep their source lines. */
    private val headerMark = Regex("^\uFEFF?# *")

    override val syncsScroll get() = true
}
