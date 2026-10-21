package dev.sluide.previews.table

import dev.sluide.fileviewers.Highlighters
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows cookie jars (`.cookies`, IntelliJ's `http-client.cookies`) as text beside a sortable, searchable table ([CookiesPreview]).
 */
@Component
class CookiesFileViewer :
    PreviewFileViewer(setOf("cookies"), Highlighters.cookies, CookiesPreview, CommentSyntax.NONE)

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
    ) = engine.loadContent(render(text, dark, TableState.read(engine)))

    fun render(
        text: String,
        dark: Boolean,
        state: TableState = TableState(),
    ): String =
        CsvRenderer.render(
            text.replaceFirst(headerMark, ""),
            '\t',
            dark,
            quotes = false,
            fixedHeader = true,
            state = state,
        )

    /** The `# ` before the header's first column; the line stays, so rows keep their source lines. */
    private val headerMark = Regex("^\uFEFF?# *")

    override val syncsScroll get() = true
}
