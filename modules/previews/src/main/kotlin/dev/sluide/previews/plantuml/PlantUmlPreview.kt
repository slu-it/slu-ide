package dev.sluide.previews.plantuml

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.plantuml.Rendering
import dev.sluide.plantuml.plantUmlBlocks
import dev.sluide.previews.loadPage
import dev.sluide.previews.preview.Preview
import javafx.application.Platform
import javafx.scene.web.WebEngine
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * A PlantUML file's diagrams ([PlantUmlPage]), drawn by [render] (the app's `PlantUml`, ADR 0193) in the background:
 * one per view ([attach]), with its own [DiagramRenders] for the whole text (first line 1, so an error's line is the
 * file's). Before the first drawing the page says "Drawing the diagram…"; while a later text is drawn the diagrams shown
 * stay as they are (no page is loaded, so zoom and scroll stay too), and the view renders again when the drawing is
 * there. An empty text shows nothing, a text without a `@start…` line says so and is not drawn. A theme change draws
 * nothing: the page holds both variants. Calls are on the FX thread, which [runLater] goes back to.
 */
internal class PlantUmlPreview(
    render: (source: String, firstLine: Int) -> CompletableFuture<Rendering>,
    runLater: (Runnable) -> Unit = Platform::runLater,
) : Preview {
    private var rerender: () -> Unit = {}

    /** A few texts are enough to go back to after an undo and a save; each keeps two SVGs per diagram. */
    private val renders = DiagramRenders(render, { rerender() }, runLater, cacheSize = 4)

    /** Whether the last page loaded shows diagrams, which then stay while a later text is drawn. */
    private var showsDiagrams = false

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
    ) {
        page(text, dark)?.let(engine::loadPage)
    }

    /** The page for [text], or null to keep the page shown, whose diagrams stay until those of [text] are there. */
    fun page(
        text: String,
        dark: Boolean,
    ): String? {
        val content =
            when {
                text.isBlank() -> {
                    null
                }

                !plantUmlBlocks(text) -> {
                    PlantUmlHtml.noBlock()
                }

                else -> {
                    val rendering = renders.get(text, 1)
                    when {
                        rendering == null -> if (showsDiagrams) return null else PlantUmlHtml.drawing()

                        // Only if the engine broke its contract: a text with a @start line has a diagram.
                        rendering.diagrams.isEmpty() -> PlantUmlHtml.noBlock()

                        else -> PlantUmlHtml.diagrams(rendering)
                    }
                }
            }
        showsDiagrams = content?.hasClass("plantuml") == true
        return PlantUmlPage.render(content, dark)
    }

    override val zooms get() = true
}
