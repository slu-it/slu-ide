package dev.sluide.previews.table

import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.extension

/** Shows CSV and TSV files (`.csv`, `.tsv`) as text beside a sortable, searchable table ([CsvRenderer]). */
@Component
class CsvFileViewer :
    PreviewFileViewer(setOf("csv", "tsv"), SyntaxHighlighter { emptyList() }, CsvPreview, CommentSyntax.NONE)

/** The rows as a table, scrolling with the source. */
internal object CsvPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(
        CsvRenderer.render(text, CsvRenderer.delimiter(file.extension, text), dark, state = TableState.read(engine)),
    )

    override val syncsScroll get() = true
}
