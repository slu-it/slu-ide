package dev.sluide.fileviewers.json

import dev.sluide.fileviewers.NamedFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import dev.sluide.fileviewers.preview.FoldState
import dev.sluide.fileviewers.preview.Preview
import dev.sluide.fileviewers.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shows JSON files (`.json`, `.jsonc`, and Pipenv's `Pipfile.lock`) as highlighted source, the side of the JSON preview
 * ([JsonPreview], ADR 0064) a [PreviewFileViewer] shows. The HTTP client's environment files are claimed by their own
 * viewer by name.
 */
@Component
class JsonFileViewer :
    PreviewFileViewer(setOf("json", "jsonc"), RSyntaxHighlighter.json, JsonPreview, CommentSyntax.NONE),
    NamedFileViewer {
    override val fileNames: Set<String> = setOf("Pipfile.lock")

    override fun canOpen(file: Path): Boolean {
        val name = file.fileName?.toString() ?: return false
        val known = matchesExtension(file) || fileNames.any { it.equals(name, ignoreCase = true) }
        return known && Files.isReadable(file) && isTextFile(file)
    }
}

/**
 * The JSON as nested, foldable blocks ([JsonRenderer]), scrolling with the source. Opens side by side with the
 * source, which takes the focus (ADR 0050), so the file can be edited at once. The page's folds are read before it is
 * replaced and passed on, so they survive saving and reloading from disk (a theme change only recolours the page).
 */
internal object JsonPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(JsonRenderer.render(text, dark, FoldState.read(engine)))

    override val syncsScroll get() = true

    override val folds get() = true
}
