package dev.sluide.fileviewers

import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Shows Jupyter notebooks (`.ipynb`) rendered ([NotebookRenderer]), with their JSON source one click away. */
@Component
class NotebookFileViewer : PreviewFileViewer(setOf("ipynb"), RSyntaxHighlighter.json, NotebookPreview)

/** The notebook with its stored outputs; its JSON lines do not match cells, so it does not scroll with the source. */
internal object NotebookPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
    ) = engine.loadContent(NotebookRenderer.render(text, file.parent, dark))

    override val defaultMode get() = PreviewMode.PREVIEW
}
