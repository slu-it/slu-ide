package dev.sluide.previews.plantuml

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
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
 * [rasterizer]; [measurer] reads the natural sizes for the custom size. Every item writes the variant the preview shows
 * (its diagram theme, [PlantUmlPreview]): [dark] says whether it is the dark one, and is asked on the FX thread when the
 * item is chosen, so that a later change of the choice leaves a chosen export as it is.
 */
internal fun plantUmlExports(
    render: PlantUmlRender,
    rasterizer: SvgRasterizer,
    measurer: SvgMeasurer,
    ask: (Window?, Pair<Int, Int>) -> Pair<Int, Int>?,
    onFx: (Runnable) -> Unit,
    cached: (String) -> Rendering? = { null },
    tasks: () -> BackgroundTasks? = { null },
    disposed: () -> Boolean = { false },
    dark: () -> Boolean = { false },
): List<FileExport> {
    fun done(
        label: String,
        png: ((Int) -> PngSize)?,
    ): (Rendering, Window?, Boolean) -> CompletionStage<FileExport?> =
        { rendering, _, isDark ->
            CompletableFuture.completedFuture(RenderedPlantUmlExport(label, rendering, isDark, rasterizer, png))
        }
    return listOf(PlantUmlExport("SVG", render, done("SVG", null), cached, tasks, disposed, onFx, dark)) +
        listOf(1, 2, 3).map { factor ->
            val label = "PNG ${factor * 100}%"
            PlantUmlExport(label, render, done(label, { PngSize.Scale(factor) }), cached, tasks, disposed, onFx, dark)
        } +
        PlantUmlExport("PNG Custom…", render, { rendering, owner, isDark ->
            customSize(rendering, isDark, owner, rasterizer, measurer, ask, onFx)
        }, cached, tasks, disposed, onFx, dark)
}

/**
 * Measures the drawn diagrams of [rendering] off the FX thread, [ask]s on it for the size of the first and completes
 * with the PNG export at the scaled sizes, or with null if the user cancelled.
 */
private fun customSize(
    rendering: Rendering,
    dark: Boolean,
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
            val naturals = drawn.map { measurer.measure((it.value as Diagram.Drawn).svg(dark).toByteArray()) }
            if (naturals.isEmpty()) {
                // Nothing to ask for: the export fails with the reason of the first failed diagram.
                chosen.complete(RenderedPlantUmlExport("PNG Custom…", rendering, dark, rasterizer) { PngSize.Scale(1) })
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
                                RenderedPlantUmlExport(
                                    "PNG Custom…",
                                    rendering,
                                    dark,
                                    rasterizer,
                                ) { bySlot.getValue(it) },
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
 * The export to run once the file is rendered: every drawn diagram of [rendering] as its SVG in the [dark] or light
 * variant, or, with [png]
 * (the size of the diagram with a 1-based number), as a PNG drawn by [rasterizer]. The files are named after the file:
 * `name.svg` for the first diagram, `name-2.svg` for the second and so on, counted over all diagrams, so that a failed
 * one keeps its number; a PNG's name has the size before the extension (`name-2@2x.png`, `name-2-640x480.png`).
 */
internal class RenderedPlantUmlExport(
    override val label: String,
    private val rendering: Rendering,
    private val dark: Boolean,
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
                writeDiagram((diagram as Diagram.Drawn).svg(dark), number, path(file, number))
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

/**
 * An item of the menu, which renders the saved text on [choose] and completes with the export [bind]ed to it and to the
 * variant [dark] says the preview shows at that moment.
 */
internal class PlantUmlExport(
    override val label: String,
    private val render: PlantUmlRender,
    private val bind: (Rendering, Window?, Boolean) -> CompletionStage<FileExport?>,
    private val cached: (String) -> Rendering?,
    private val tasks: () -> BackgroundTasks?,
    private val disposed: () -> Boolean,
    private val onFx: (Runnable) -> Unit,
    private val dark: () -> Boolean,
) : FileExport {
    override fun target(file: Path): Path = throw IllegalStateException("Choose first")

    override fun targets(file: Path): List<Path> = throw IllegalStateException("Choose first")

    override fun choose(
        file: Path,
        owner: Window?,
        text: String,
    ): CompletionStage<FileExport?> {
        val isDark = dark()
        val held = cached(text)
        // A timeout or a start failure has no line: drawing again may do better, a syntax error would not.
        if (held != null &&
            held.diagrams.none { it is Diagram.Failed && it.line == null }
        ) {
            return bind(held, owner, isDark)
        }
        val chosen = CompletableFuture<FileExport?>()
        val row = DrawingRow()
        row.start(tasks(), "Drawing ${file.fileName} for the export") {
            // The engine cannot stop a drawing: its result is dropped when it comes.
            chosen.complete(null)
        }
        // The decision is made on the FX thread, where a cancel and the view's disposal happen too.
        render(text, 1).whenComplete { rendering, error ->
            onFx {
                when {
                    // Cancelled: the row ended with the cancel.
                    chosen.isDone -> {}

                    error != null -> {
                        row.failed(error)
                        chosen.completeExceptionally(error)
                    }

                    else -> {
                        row.close()
                        if (disposed()) {
                            chosen.complete(
                                null,
                            )
                        } else {
                            bind(rendering, owner, isDark).whenComplete(chosen::finish)
                        }
                    }
                }
            }
        }
        return chosen
    }

    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> = throw IllegalStateException("Choose first")
}

/** Completes this future as a stage that bound an export did: with its export, or its failure. */
private fun CompletableFuture<FileExport?>.finish(
    export: FileExport?,
    error: Throwable?,
) {
    if (error != null) completeExceptionally(error) else complete(export)
}

/** The row of a window's tasks for a drawing, if the window has tasks; a cancel ends it at once as cancelled. */
private class DrawingRow {
    @Volatile private var task: BackgroundTask? = null

    fun start(
        tasks: BackgroundTasks?,
        name: String,
        onCancel: () -> Unit,
    ) {
        if (tasks == null) return
        task =
            tasks.start(name) {
                onCancel()
                cancelled()
            }
    }

    fun close() {
        val row = task
        if (row != null) row.close()
    }

    fun failed(error: Throwable) {
        val row = task
        if (row != null) row.failed(error)
    }

    private fun cancelled() {
        val row = task
        if (row != null) row.failed("Cancelled")
    }
}

/** The SVG of this diagram in the [dark] or the light variant. */
internal fun Diagram.Drawn.svg(dark: Boolean): String = if (dark) darkSvg else lightSvg
