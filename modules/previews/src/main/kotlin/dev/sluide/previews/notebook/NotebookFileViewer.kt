package dev.sluide.previews.notebook

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.Highlighters
import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.loadPage
import dev.sluide.previews.plantuml.DiagramRenders
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.previews.preview.PreviewMode
import dev.sluide.textediting.CommentSyntax
import javafx.application.Platform
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Shows Jupyter notebooks (`.ipynb`) rendered ([NotebookRenderer]), with their JSON source one click away; PlantUML
 * fences in Markdown cells are drawn by [render] (the app's `PlantUml`, ADR 0193), each view having its own
 * [NotebookPreview]. Spring uses the constructor without arguments.
 */
@Component
class NotebookFileViewer internal constructor(
    private val render: (source: String, firstLine: Int) -> CompletableFuture<Rendering>,
) : PreviewFileViewer(
        setOf("ipynb"),
        Highlighters.json,
        // Never shown: every view gets its own preview (newPreview).
        NotebookPreview(render),
        CommentSyntax.NONE,
    ) {
    constructor() : this({ source, firstLine -> PlantUml.shared.render(source, firstLine) })

    override fun newPreview(): Preview = NotebookPreview(render)
}

/**
 * The notebook with its stored outputs; its JSON lines do not match cells, so it does not scroll with the source. One
 * per view ([attach]), with its own [DiagramRenders] for the PlantUML fences of Markdown cells, drawn by [render] in the
 * background: the view renders again when a drawing is there. Calls are on the FX thread, which [runLater] goes back
 * to.
 */
internal class NotebookPreview(
    render: (source: String, firstLine: Int) -> CompletableFuture<Rendering>,
    runLater: (Runnable) -> Unit = Platform::runLater,
) : Preview {
    private var rerender: () -> Unit = {}

    private val renders = DiagramRenders(render, { rerender() }, runLater)

    override fun attach(
        rerender: () -> Unit,
        host: () -> FileViewHost?,
    ) {
        this.rerender = rerender
    }

    override fun detach() = renders.detach()

    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadPage(page(text, file.parent, dark))

    /** The page of the notebook [text], a file in [folder]. */
    fun page(
        text: String,
        folder: Path,
        dark: Boolean,
    ): String = NotebookRenderer.render(text, folder, dark, renders::get)

    override val defaultMode get() = PreviewMode.PREVIEW
}
