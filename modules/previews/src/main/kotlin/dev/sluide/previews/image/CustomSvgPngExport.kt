package dev.sluide.previews.image

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.previews.preview.FileExport
import javafx.application.Platform
import javafx.stage.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The SVG as a PNG of a size the user asks for: [choose] measures the SVG (with [measurer], off the FX thread), asks
 * for the size in a [PngSizeDialog] prefilled with it, and completes with a [SvgPngExport] at that size (drawn by
 * [rasterizer]), or with null if the user cancelled. This item itself writes nothing.
 */
internal class CustomSvgPngExport(
    private val measurer: SvgMeasurer = WebViewSvgRasterizer(),
    private val rasterizer: SvgRasterizer = WebViewSvgRasterizer(),
) : FileExport {
    override val label get() = "PNG Custom…"

    /** Asks for the size, given the natural size; tests replace it, since it blocks. */
    var ask: (Window?, Pair<Int, Int>) -> Pair<Int, Int>? =
        { owner, natural -> PngSizeDialog(owner, natural).showAndWait().orElse(null) }

    override fun choose(
        file: Path,
        owner: Window?,
    ): CompletionStage<FileExport?> {
        val chosen = CompletableFuture<FileExport?>()
        NamedThreads.virtual("sluide-export") {
            try {
                val natural = measurer.measure(Files.readAllBytes(file))
                Platform.runLater {
                    try {
                        val size = ask(owner, natural)
                        chosen.complete(size?.let { SvgPngExport(PngSize.Pixels(it.first, it.second), rasterizer) })
                    } catch (e: Throwable) {
                        chosen.completeExceptionally(e)
                    }
                }
            } catch (e: Throwable) {
                chosen.completeExceptionally(e)
            }
        }
        return chosen
    }

    override fun target(file: Path): Path = throw IllegalStateException("Choose a size first")

    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> = throw IllegalStateException("Choose a size first")
}
