package dev.sluide.fileviewers

import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.extension

/** Shows CSV and TSV files (`.csv`, `.tsv`) as text beside a sortable table ([CsvRenderer]). */
@Component
class CsvFileViewer : PreviewFileViewer(setOf("csv", "tsv"), SyntaxHighlighter { emptyList() }, CsvPreview)

/** The rows as a table, scrolling with the source. */
internal object CsvPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(CsvRenderer.render(text, CsvRenderer.delimiter(file.extension, text), dark))

    override val syncsScroll get() = true
}
