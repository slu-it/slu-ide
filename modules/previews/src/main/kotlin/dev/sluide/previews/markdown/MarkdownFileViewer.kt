package dev.sluide.previews.markdown

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.Highlighters
import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.loadPage
import dev.sluide.previews.plantuml.DiagramRenders
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.application.Platform
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Shows Markdown documents (`.md`, `.markdown`) as highlighted source beside a rendered preview (ADR 0016); see
 * [MarkdownRenderer]. PlantUML fences are drawn by [render] (the app's `PlantUml`, ADR 0193); each view gets its own
 * [MarkdownPreview]. Spring uses the constructor without arguments.
 */
@Component
class MarkdownFileViewer internal constructor(
    private val render: (source: String, firstLine: Int) -> CompletableFuture<Rendering>,
) : PreviewFileViewer(
        setOf("md", "markdown"),
        Highlighters.markdown,
        // Never shown: every view gets its own preview (newPreview).
        MarkdownPreview(render),
        CommentSyntax.XML,
    ) {
    constructor() : this({ source, firstLine -> PlantUml.shared.render(source, firstLine) })

    override fun opensContent(name: String) = hasExtension(name)

    override fun newPreview(): Preview = MarkdownPreview(render)
}

/**
 * Markdown rendered by [MarkdownRenderer], scrolling with the source; one per view ([attach]), with its own
 * [DiagramRenders] for the PlantUML fences, drawn by [render] in the background: a fence says "Drawing the diagram…"
 * until its drawing is there, then the view renders again (as for a save: the page keeps its place). Calls are on the
 * FX thread, which [runLater] goes back to.
 */
internal class MarkdownPreview(
    render: (source: String, firstLine: Int) -> CompletableFuture<Rendering>,
    runLater: (Runnable) -> Unit = Platform::runLater,
) : Preview {
    private var rerender: () -> Unit = {}

    private val renders = DiagramRenders(render, { rerender() }, runLater)

    private val diagrams = FenceDiagrams(renders::get)

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

    /** The page of [text], a file in [folder]. */
    fun page(
        text: String,
        folder: Path,
        dark: Boolean,
    ): String = MarkdownRenderer.render(text, folder, dark, diagrams)

    override val syncsScroll get() = true

    override val mirrorsHighlights get() = true

    override val hasOutline get() = true

    override fun outline(text: String) = MarkdownOutline.headings(text)

    override val exports by lazy { listOf(MarkdownHtmlExport()) }
}
