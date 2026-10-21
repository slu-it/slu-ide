package dev.sluide.previews.notebook

import dev.sluide.fileviewers.Highlighters
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.previews.preview.PreviewMode
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Shows Jupyter notebooks (`.ipynb`) rendered ([NotebookRenderer]), with their JSON source one click away. */
@Component
class NotebookFileViewer : PreviewFileViewer(setOf("ipynb"), Highlighters.json, NotebookPreview, CommentSyntax.NONE)

/** The notebook with its stored outputs; its JSON lines do not match cells, so it does not scroll with the source. */
internal object NotebookPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(NotebookRenderer.render(text, file.parent, dark))

    override val defaultMode get() = PreviewMode.PREVIEW
}
