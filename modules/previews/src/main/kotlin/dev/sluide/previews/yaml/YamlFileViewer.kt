package dev.sluide.previews.yaml

import dev.sluide.fileviewers.Highlighters
import dev.sluide.previews.preview.FoldState
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows YAML files (`.yml`, `.yaml`) as highlighted source, the side of the YAML preview ([YamlPreview], ADR 0079) a
 * [PreviewFileViewer] shows. GitHub Actions workflows (YAML files in `.github/workflows`) have their own viewer,
 * which their path picks.
 */
@Component
class YamlFileViewer :
    PreviewFileViewer(
        setOf("yml", "yaml"),
        Highlighters.yaml,
        YamlPreview,
        CommentSyntax.HASH,
    )

/**
 * The YAML as nested, foldable blocks ([YamlRenderer]), scrolling with the source. Opens side by side with the source,
 * which takes the focus (ADR 0050), so the file can be edited at once. The page's folds are read before it is replaced
 * and passed on, so they survive saving and reloading from disk (a theme change only recolours the page).
 */
internal object YamlPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(YamlRenderer.render(text, dark, FoldState.read(engine)))

    override val syncsScroll get() = true

    override val folds get() = true

    override val copiesFormatted get() = true

    override fun formatted(text: String) = YamlText.format(text)
}
