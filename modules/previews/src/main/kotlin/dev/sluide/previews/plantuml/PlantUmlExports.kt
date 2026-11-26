package dev.sluide.previews.plantuml

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.image.PngSize
import dev.sluide.previews.image.SvgMeasurer
import dev.sluide.previews.image.SvgRasterizer
import dev.sluide.previews.image.pngSuffix
import dev.sluide.previews.preview.FileExport
import dev.sluide.previews.preview.writeReplacing
import javafx.stage.Window
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText
import kotlin.math.roundToInt

internal typealias PlantUmlRender = (source: String, firstLine: Int) -> CompletableFuture<Rendering>

/**
 * The Export menu of a PlantUML file's preview: SVG, PNG at 100, 200 and 300% and PNG Custom…, which [ask]s for the
 * size of the first diagram on the FX thread (through [onFx]) and scales the others alike. The PNGs are drawn by
 * [rasterizer]; [measurer] reads the natural sizes for the custom size.
 */
internal fun plantUmlExports(
    render: PlantUmlRender,
    rasterizer: SvgRasterizer,
    measurer: SvgMeasurer,
    ask: (Window?, Pair<Int, Int>) -> Pair<Int, Int>?,
    onFx: (Runnable) -> Unit,
): List<FileExport> {
    fun done(
        label: String,
        png: ((Int) -> PngSize)?,
    ): (Rendering, Window?) -> CompletionStage<FileExport?> =
        { rendering, _ ->
            CompletableFuture.completedFuture(RenderedPlantUmlExport(label, rendering, rasterizer, png))
        }
    return listOf(PlantUmlExport("SVG", render, done("SVG", null))) +
        listOf(1, 2, 3).map { factor ->
            val label = "PNG ${factor * 100}%"
            PlantUmlExport(label, render, done(label, { PngSize.Scale(factor) }))
        } +
        PlantUmlExport("PNG Custom…", render) { rendering, owner ->
            customSize(rendering, owner, rasterizer, measurer, ask, onFx)
        }
}

/**
 * Measures the drawn diagrams of [rendering] off the FX thread, [ask]s on it for the size of the first and completes
 * with the PNG export at the scaled sizes, or with null if the user cancelled.
 */
private fun customSize(
    rendering: Rendering,
    owner: Window?,
    rasterizer: SvgRasterizer,
    measurer: SvgMeasurer,
    ask: (Window?, Pair<Int, Int>) -> Pair<Int, Int>?,
    onFx: (Runnable) -> Unit,
): CompletionStage<FileExport?> {
    val chosen = CompletableFuture<FileExport?>()
    NamedThreads.virtual("sluide-export") {
        try {
            val drawn = rendering.diagrams.withIndex().filter { it.value is Diagram.Drawn }
            val naturals = drawn.map { measurer.measure((it.value as Diagram.Drawn).lightSvg.toByteArray()) }
            if (naturals.isEmpty()) {
                // Nothing to ask for: the export fails with the reason of the first failed diagram.
                chosen.complete(RenderedPlantUmlExport("PNG Custom…", rendering, rasterizer) { PngSize.Scale(1) })
            } else {
                onFx {
                    try {
                        val size = ask(owner, naturals.first())
                        if (size == null) {
                            chosen.complete(null)
                        } else {
                            val numbers = drawn.map { it.index + 1 }
                            val pixels = customSizes(naturals, size).map { PngSize.Pixels(it.first, it.second) }
                            val bySlot = numbers.zip(pixels).toMap()
                            chosen.complete(
                                RenderedPlantUmlExport("PNG Custom…", rendering, rasterizer) { bySlot.getValue(it) },
                            )
                        }
                    } catch (e: Throwable) {
                        chosen.completeExceptionally(e)
                    }
                }
            }
        } catch (e: Throwable) {
            chosen.completeExceptionally(e)
        }
    }
    return chosen
}

/**
 * The sizes of the PNGs of diagrams of natural sizes [naturals] when the first is drawn at [chosen]: the others are
 * scaled by the same factor (their width by the chosen width over the first's, their height by their own ratio), none
 * below one pixel.
 */
internal fun customSizes(
    naturals: List<Pair<Int, Int>>,
    chosen: Pair<Int, Int>,
): List<Pair<Int, Int>> {
    val (firstWidth, _) = naturals.first()
    return naturals.mapIndexed { index, (width, height) ->
        if (index == 0) {
            chosen
        } else {
            val scaledWidth = (chosen.first.toDouble() * width / firstWidth).roundToInt().coerceAtLeast(1)
            scaledWidth to (scaledWidth.toDouble() * height / width).roundToInt().coerceAtLeast(1)
        }
    }
}

/**
 * The export to run once the file is rendered: every drawn diagram of [rendering] as its light SVG, or, with [png]
 * (the size of the diagram with a 1-based number), as a PNG drawn by [rasterizer]. The files are named after the file:
 * `name.svg` for the first diagram, `name-2.svg` for the second and so on, counted over all diagrams, so that a failed
 * one keeps its number; a PNG's name has the size before the extension (`name-2@2x.png`, `name-2-640x480.png`).
 */
internal class RenderedPlantUmlExport(
    override val label: String,
    private val rendering: Rendering,
    private val rasterizer: SvgRasterizer,
    private val png: ((index: Int) -> PngSize)?,
) : FileExport {
    override fun target(file: Path): Path = targets(file).firstOrNull() ?: path(file, 1)

    override fun targets(file: Path): List<Path> =
        rendering.diagrams
            .withIndex()
            .filter { it.value is Diagram.Drawn }
            .map { path(file, it.index + 1) }

    private fun path(
        file: Path,
        number: Int,
    ): Path {
        val size = png
        val extension = if (size == null) ".svg" else pngSuffix(size(number)) + ".png"
        return file.resolveSibling(file.nameWithoutExtension + (if (number == 1) "" else "-$number") + extension)
    }

    /** Writes every drawn diagram, whatever [target] is (the first of the [targets]); a failed one is a problem. */
    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> {
        if (rendering.diagrams.none { it is Diagram.Drawn }) {
            val failed = rendering.diagrams.filterIsInstance<Diagram.Failed>()
            throw IOException(if (failed.isEmpty()) "No diagram to export" else failure(failed.first()))
        }
        val problems = mutableListOf<String>()
        var anyWritten = false
        var firstError: IOException? = null
        for ((index, diagram) in rendering.diagrams.withIndex()) {
            val number = index + 1
            if (diagram is Diagram.Failed) {
                problems += "Diagram $number not exported: ${failure(diagram)}"
                continue
            }
            try {
                writeDiagram((diagram as Diagram.Drawn).lightSvg, number, path(file, number))
                anyWritten = true
            } catch (e: IOException) {
                // The others are still written: no diagram's failure undoes the replaced files before it.
                if (firstError == null) firstError = e
                problems += "Diagram $number not exported: ${e.message ?: "I/O error"}"
            }
        }
        if (!anyWritten && firstError != null) throw firstError
        return problems
    }

    private fun writeDiagram(
        svg: String,
        number: Int,
        target: Path,
    ) {
        val size = png
        if (size == null) {
            writeReplacing(target) { it.writeText(svg, Charsets.UTF_8) }
            return
        }
        val image = rasterizer.rasterize(svg.toByteArray(Charsets.UTF_8), size(number))
        writeReplacing(target) { if (!ImageIO.write(image, "png", it.toFile())) throw IOException("No PNG writer") }
    }

    private fun failure(failed: Diagram.Failed) =
        if (failed.line == null) failed.message else "Line ${failed.line}: ${failed.message}"
}

/** An item of the menu, which renders the saved text on [choose] and completes with the export [bind]ed to it. */
internal class PlantUmlExport(
    override val label: String,
    private val render: PlantUmlRender,
    private val bind: (Rendering, Window?) -> CompletionStage<FileExport?>,
) : FileExport {
    override fun target(file: Path): Path = throw IllegalStateException("Choose first")

    override fun targets(file: Path): List<Path> = throw IllegalStateException("Choose first")

    override fun choose(
        file: Path,
        owner: Window?,
        text: String,
    ): CompletionStage<FileExport?> = render(text, 1).thenCompose { bind(it, owner) }

    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> = throw IllegalStateException("Choose first")
}
