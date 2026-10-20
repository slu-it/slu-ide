package dev.sluide.fileviewers.json

import dev.sluide.fileviewers.NamedFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import dev.sluide.fileviewers.preview.Preview
import dev.sluide.fileviewers.preview.PreviewFileViewer
import dev.sluide.fileviewers.preview.PreviewMode
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import netscape.javascript.JSException
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
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
 * The JSON as nested, foldable blocks ([JsonRenderer]), scrolling with the source. Opens on the source, since most
 * JSON files are edited rather than read. The page's folds are read before it is replaced and passed on, so they
 * survive saving and reloading from disk (a theme change only recolours the page).
 */
internal object JsonPreview : Preview {
    private val mapper = JsonMapper()

    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(JsonRenderer.render(text, dark, collapsedPaths(engine)))

    override val syncsScroll get() = true

    override val defaultMode get() = PreviewMode.SOURCE

    override val folds get() = true

    /** The JSON Pointers of the containers the current page has closed; none if there is no such page. */
    private fun collapsedPaths(engine: WebEngine): Set<String> =
        try {
            val script = "window.sluide && sluide.json ? sluide.json.collapsed() : null"
            (engine.executeScript(script) as? String)
                ?.let(mapper::readTree)
                ?.takeIf { it.isArray }
                ?.mapNotNull { it.asString(null) }
                ?.toSet()
                .orEmpty()
        } catch (_: JSException) {
            emptySet()
        } catch (_: JacksonException) {
            emptySet()
        }
}
