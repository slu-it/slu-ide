package dev.sluide.previews.plantuml

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.image.PngSize
import dev.sluide.previews.image.SvgMeasurer
import dev.sluide.previews.image.SvgRasterizer
import dev.sluide.previews.preview.FileExport
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Export menu of a PlantUML file's preview: its items, the files they name and what they write. */
class PlantUmlExportsTest {
    @TempDir
    lateinit var dir: Path

    private val renderedTexts = CopyOnWriteArrayList<Pair<String, Int>>()
    private var rendering = Rendering(emptyList(), false)
    private var pending: CompletableFuture<Rendering>? = null

    /** What the fake rasteriser was asked for: the SVG and the size. */
    private val rasterized = CopyOnWriteArrayList<Pair<String, PngSize>>()

    /** Draws a transparent image of 4 × 3 pixels per scale, with one red pixel. */
    private val rasterizer =
        SvgRasterizer { svg, size ->
            rasterized += svg.decodeToString() to size
            // What WebViewSvgRasterizer does with a drawing beyond its limits.
            val text = svg.decodeToString()
            if ("2x2" in text) throw IOException("Too large")
            if ("3x3" in text) throw IOException("Huge")
            if ("4x4" in text) throw IOException()
            val (width, height) = size.pixels(4, 3)
            BufferedImage(width.toInt(), height.toInt(), BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, 0xffff0000.toInt())
            }
        }

    /** Sizes by the SVG's text: `<svg W×H>`. */
    private val measured = CopyOnWriteArrayList<String>()

    private val measurer =
        SvgMeasurer { svg ->
            measured += svg.decodeToString()
            val (width, height) =
                svg
                    .decodeToString()
                    .substringAfter('<')
                    .substringBefore('>')
                    .split(' ')[1]
                    .split('x')
            width.toInt() to height.toInt()
        }

    private val asked = CopyOnWriteArrayList<Pair<String, Pair<Int, Int>>>()
    private var answer: Pair<Int, Int>? = 50 to 25
    private var askError: Throwable? = null
    private var fxQueue = ArrayDeque<Runnable>()

    private fun exportsWith(
        cached: (String) -> Rendering? = { null },
        tasks: () -> BackgroundTasks? = { null },
        disposed: () -> Boolean = { false },
    ) = plantUmlExports(
        { source, line ->
            renderedTexts += source to line
            pending ?: CompletableFuture.completedFuture(rendering)
        },
        rasterizer,
        measurer,
        { _, natural ->
            asked += "asked" to natural
            askError?.let { throw it }
            answer
        },
        { synchronized(fxQueue) { fxQueue.addLast(it) } },
        cached,
        tasks,
        disposed,
        { previewDark },
    )

    /** The variant the preview shows (its choice, or the app's scheme for Default). */
    private var previewDark = false

    private val exports = exportsWith()

    private fun item(label: String) = exports.single { it.label == label }

    private fun drawn(
        size: String,
        dark: String = "dark",
    ) = Diagram.Drawn("<svg $size>light", "<svg $size>$dark")

    private val file get() = dir.resolve("diagram.puml")

    /** Chooses [label] for [file] and waits for the export it completes with, running what is left for the FX thread. */
    private fun chosen(
        label: String,
        file: Path = this.file,
    ): FileExport? {
        val future = item(label).choose(file, null, "text").toCompletableFuture()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone) {
            check(System.nanoTime() < deadline) { "Not chosen" }
            synchronized(fxQueue) { fxQueue.removeFirstOrNull() }?.run() ?: Thread.sleep(5)
        }
        return future.get()
    }

    /** Runs what was handed to the FX thread. */
    private fun <T> awaited(future: CompletableFuture<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone) {
            check(System.nanoTime() < deadline) { "Not completed" }
            synchronized(fxQueue) { fxQueue.removeFirstOrNull() }?.run() ?: Thread.sleep(5)
        }
        return future.get()
    }

    private fun drain() {
        while (true) (synchronized(fxQueue) { fxQueue.removeFirstOrNull() } ?: return).run()
    }

    /** The task lines of a window's tasks, with the task number left out. */
    private val lines = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests { lines += it.replace(Regex("task#\\d+"), "task") }

    @Test
    fun `a rendering the view holds is used at once, without a render or a task`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        val held = rendering
        pending = CompletableFuture() // a render would never arrive
        val future =
            exportsWith({ if (it == "text") held else null }, { tasks })
                .first()
                .choose(file, null, "text")
                .toCompletableFuture()
        assertTrue(future.isDone)
        assertEquals(listOf(dir.resolve("diagram.svg")), future.get()!!.targets(file))
        assertEquals(emptyList(), renderedTexts)
        assertEquals(emptyList(), lines)
    }

    @Test
    fun `a cancel before the FX thread decides on the arrived drawing asks nothing`() {
        rendering = Rendering(listOf(drawn("100x50")), false)
        pending = CompletableFuture()
        val custom = exportsWith(tasks = { tasks }).last()
        val future = custom.choose(file, null, "text").toCompletableFuture()
        pending!!.complete(rendering)
        tasks.shown.single().cancel()
        drain()
        // Nothing to wait for: a measurement would start on its own thread at once.
        Thread.sleep(200)
        assertEquals(emptyList(), measured)
        assertEquals(emptyList(), asked)
        assertNull(future.get())
    }

    @Test
    fun `a dispose before the FX thread decides on the arrived drawing asks nothing`() {
        rendering = Rendering(listOf(drawn("100x50")), false)
        pending = CompletableFuture()
        var disposed = false
        val custom = exportsWith(tasks = { tasks }, disposed = { disposed }).last()
        val future = custom.choose(file, null, "text").toCompletableFuture()
        pending!!.complete(rendering)
        disposed = true
        drain()
        Thread.sleep(200) // as above: only the measuring thread is left to wait for
        assertEquals(emptyList(), measured)
        assertEquals(emptyList(), asked)
        assertNull(future.get())
    }

    @Test
    fun `a held rendering with a failure that has no line is drawn again`() {
        pending = CompletableFuture()
        val held = Rendering(listOf(drawn("1x1"), Diagram.Failed("Drawing took longer than 20 s", null)), false)
        val future =
            exportsWith({ held }, { tasks }).first().choose(file, null, "text").toCompletableFuture()
        assertFalse(future.isDone)
        assertEquals(listOf("text" to 1), renderedTexts)
    }

    @Test
    fun `a held rendering whose failures have lines is used`() {
        val held = Rendering(listOf(drawn("1x1"), Diagram.Failed("Syntax Error?", 3)), false)
        val future = exportsWith({ held }).first().choose(file, null, "text").toCompletableFuture()
        assertTrue(future.isDone)
        assertEquals(emptyList(), renderedTexts)
    }

    @Test
    fun `a rendering that is not held is drawn as a task of the window, which ends with it`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        pending = CompletableFuture()
        val future = exportsWith(tasks = { tasks }).first().choose(file, null, "text").toCompletableFuture()
        assertEquals(listOf("task started: Drawing diagram.puml for the export"), lines)
        assertEquals(listOf("Drawing diagram.puml for the export"), tasks.shown.map { it.name.get() })
        pending!!.complete(rendering)
        drain()
        assertEquals(listOf(dir.resolve("diagram.svg")), future.get(10, TimeUnit.SECONDS)!!.targets(file))
        assertTrue(lines.last().startsWith("task finished in "), lines.toString())
        assertEquals(emptyList(), tasks.shown.toList())
    }

    @Test
    fun `cancelling the task cancels the export, nothing is asked once the drawing arrives`() {
        rendering = Rendering(listOf(drawn("100x50")), false)
        pending = CompletableFuture()
        val custom = exportsWith(tasks = { tasks }).last()
        val future = custom.choose(file, null, "text").toCompletableFuture()
        tasks.shown.single().cancel()
        assertTrue(future.isDone)
        assertNull(future.get())
        pending!!.complete(rendering)
        drain()
        // Nothing to wait for: a measurement would start on its own thread at once.
        Thread.sleep(200)
        assertEquals(emptyList(), measured)
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), dir.listDirectoryEntries())
        assertTrue(lines.last().startsWith("task cancelled after "), lines.toString())
        assertEquals(emptyList(), tasks.shown.toList())
    }

    @Test
    fun `a view disposed while the drawing is made chooses nothing and asks nothing`() {
        rendering = Rendering(listOf(drawn("100x50")), false)
        pending = CompletableFuture()
        var disposed = false
        val custom = exportsWith(tasks = { tasks }, disposed = { disposed }).last()
        val future = custom.choose(file, null, "text").toCompletableFuture()
        disposed = true
        pending!!.complete(rendering)
        drain()
        assertNull(future.get(10, TimeUnit.SECONDS))
        assertEquals(emptyList(), measured)
        assertEquals(emptyList(), asked)
        assertTrue(lines.last().startsWith("task finished in "), lines.toString())
    }

    @Test
    fun `a failing drawing ends the task as failed`() {
        pending = CompletableFuture()
        val future = exportsWith(tasks = { tasks }).first().choose(file, null, "text").toCompletableFuture()
        pending!!.completeExceptionally(IllegalStateException("engine"))
        drain()
        assertFailsWith<java.util.concurrent.ExecutionException> { future.get(10, TimeUnit.SECONDS) }
        assertTrue(lines.last().startsWith("task failed after "), lines.toString())
    }

    @Test
    fun `a failing drawing fails the choice also without tasks`() {
        pending = CompletableFuture()
        val future = exportsWith().first().choose(file, null, "text").toCompletableFuture()
        pending!!.completeExceptionally(IllegalStateException("engine"))
        drain()
        assertFailsWith<java.util.concurrent.ExecutionException> { future.get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `the menu lists SVG and the PNG sizes in order`() {
        assertEquals(listOf("SVG", "PNG 100%", "PNG 200%", "PNG 300%", "PNG Custom…"), exports.map { it.label })
    }

    @Test
    fun `choosing renders the saved text from its first line and completes with an export of that rendering`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        val export = assertNotNull(chosen("SVG"))
        assertEquals(listOf("text" to 1), renderedTexts)
        assertEquals("SVG", export.label)
        assertEquals(listOf(dir.resolve("diagram.svg")), export.targets(file))
    }

    @Test
    fun `the item completes once the rendering is there, however late`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        pending = CompletableFuture()
        val future = item("SVG").choose(file, null, "text").toCompletableFuture()
        assertFalse(future.isDone)
        Thread { pending!!.complete(rendering) }.start()
        assertEquals(listOf(dir.resolve("diagram.svg")), awaited(future)!!.targets(file))
    }

    @Test
    fun `the SVG of one diagram is named after the file, of several numbered, the failed ones keep their number`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        assertEquals(listOf(dir.resolve("diagram.svg")), chosen("SVG")!!.targets(file))

        rendering = Rendering(listOf(drawn("1x1"), drawn("1x1"), drawn("1x1")), false)
        assertEquals(
            listOf("diagram.svg", "diagram-2.svg", "diagram-3.svg"),
            chosen("SVG")!!.targets(file).map { it.name },
        )

        rendering = Rendering(listOf(drawn("1x1"), Diagram.Failed("bad", 7), drawn("1x1")), false)
        assertEquals(listOf("diagram.svg", "diagram-3.svg"), chosen("SVG")!!.targets(file).map { it.name })
    }

    @Test
    fun `the PNGs are named like the SVG with the scale before the extension`() {
        rendering = Rendering(listOf(drawn("1x1"), Diagram.Failed("bad", null), drawn("1x1")), false)
        assertEquals(
            listOf(
                "PNG 100%" to listOf("diagram.png", "diagram-3.png"),
                "PNG 200%" to listOf("diagram@2x.png", "diagram-3@2x.png"),
                "PNG 300%" to listOf("diagram@3x.png", "diagram-3@3x.png"),
            ),
            listOf("PNG 100%", "PNG 200%", "PNG 300%").map { label ->
                label to chosen(label)!!.targets(file).map { it.name }
            },
        )
    }

    @Test
    fun `a custom size is asked for the first diagram, the others are scaled alike and named with theirs`() {
        rendering = Rendering(listOf(Diagram.Failed("bad", 1), drawn("100x50"), drawn("200x40")), false)
        answer = 50 to 25
        val export = assertNotNull(chosen("PNG Custom…"))
        assertEquals(listOf("asked" to (100 to 50)), asked)
        assertEquals(listOf("diagram-2-50x25.png", "diagram-3-100x20.png"), export.targets(file).map { it.name })
        assertEquals("PNG Custom…", export.label)

        export.write(file, "text", export.targets(file).first())
        assertEquals(
            listOf<Pair<String, PngSize>>(
                "<svg 100x50>light" to PngSize.Pixels(50, 25),
                "<svg 200x40>light" to PngSize.Pixels(100, 20),
            ),
            rasterized,
        )
    }

    @Test
    fun `a custom size without a drawn diagram asks nothing and writing fails with the first failure`() {
        rendering = Rendering(listOf(Diagram.Failed("bad", 3)), false)
        val export = assertNotNull(chosen("PNG Custom…"))
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), export.targets(file))
        assertEquals(dir.resolve("diagram.png"), export.target(file))
        assertEquals("Line 3: bad", assertFailsWith<IOException> { export.write(file, "text", file) }.message)
    }

    @Test
    fun `a measurement or a dialog that fails fails the choice`() {
        rendering = Rendering(listOf(drawn("boom")), false)
        assertFailsWith<java.util.concurrent.ExecutionException> { chosen("PNG Custom…") }

        rendering = Rendering(listOf(drawn("1x1")), false)
        askError = IllegalStateException("no dialog")
        assertEquals(
            "no dialog",
            assertFailsWith<java.util.concurrent.ExecutionException> { chosen("PNG Custom…") }.cause?.message,
        )
    }

    @Test
    fun `the unchosen items name no file and write nothing`() {
        assertFailsWith<IllegalStateException> { item("SVG").target(file) }
        assertFailsWith<IllegalStateException> { item("SVG").targets(file) }
        assertFailsWith<IllegalStateException> { item("SVG").write(file, "text", file) }
    }

    @Test
    fun `the target of an export is the file of its first diagram`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        assertEquals(dir.resolve("diagram@2x.png"), chosen("PNG 200%")!!.target(file))
    }

    @Test
    fun `cancelling the custom size chooses nothing`() {
        rendering = Rendering(listOf(drawn("100x50")), false)
        answer = null
        assertNull(chosen("PNG Custom…"))
    }

    @Test
    fun `the sizes of several diagrams follow the width of the first, the first keeps what was chosen`() {
        assertEquals(
            listOf(640 to 480, 320 to 96, 6 to 6),
            customSizes(listOf(100 to 75, 50 to 15, 1 to 1), 640 to 480),
        )
        // A diagram that would round to nothing keeps one pixel.
        assertEquals(listOf(10 to 1, 1 to 1), customSizes(listOf(1000 to 10, 10 to 1), 10 to 1))
        // The first keeps the chosen height, even when it does not fit its ratio.
        assertEquals(listOf(640 to 400, 320 to 96), customSizes(listOf(100 to 75, 50 to 15), 640 to 400))
    }

    @Test
    fun `writing the SVG puts the light variant of every drawn diagram beside the file as UTF-8`() {
        rendering = Rendering(listOf(drawn("1x1"), Diagram.Drawn("<svg 1x1>ü light", "dark")), false)
        val export = chosen("SVG")!!
        dir.resolve("diagram-2.svg").writeText("old")
        assertEquals(emptyList(), export.write(file, "text", export.targets(file).first()))
        assertEquals("<svg 1x1>light", dir.resolve("diagram.svg").readText())
        assertEquals("<svg 1x1>ü light", dir.resolve("diagram-2.svg").readText())
        assertEquals(listOf("diagram-2.svg", "diagram.svg"), dir.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `the SVG is the dark variant when the preview shows it when the item is chosen, not when it is written`() {
        rendering = Rendering(listOf(drawn("1x1"), drawn("1x1", "dark 2")), false)
        previewDark = true
        val export = chosen("SVG")!!
        previewDark = false
        export.write(file, "text", export.targets(file).first())
        assertEquals("<svg 1x1>dark", dir.resolve("diagram.svg").readText())
        assertEquals("<svg 1x1>dark 2", dir.resolve("diagram-2.svg").readText())
    }

    @Test
    fun `the variant is read when the item is chosen, also for a rendering the view holds`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        val held = rendering
        previewDark = true
        val export =
            exportsWith({ held })
                .first()
                .choose(file, null, "text")
                .toCompletableFuture()
                .get()!!
        previewDark = false
        export.write(file, "text", export.targets(file).first())
        assertEquals("<svg 1x1>dark", dir.resolve("diagram.svg").readText())
    }

    @Test
    fun `a PNG is drawn from the dark variant when the preview shows it`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        previewDark = true
        val export = chosen("PNG 300%")!!
        export.write(file, "text", export.targets(file).first())
        assertEquals(listOf<Pair<String, PngSize>>("<svg 1x1>dark" to PngSize.Scale(3)), rasterized)
    }

    @Test
    fun `a custom size is asked for the size of the dark variant and drawn from it`() {
        rendering = Rendering(listOf(Diagram.Drawn("<svg 100x50>light", "<svg 80x40>dark")), false)
        previewDark = true
        answer = 40 to 20
        val export = chosen("PNG Custom…")!!
        assertEquals(listOf("asked" to (80 to 40)), asked)
        assertEquals(listOf("<svg 80x40>dark"), measured)
        export.write(file, "text", export.targets(file).first())
        assertEquals(listOf<Pair<String, PngSize>>("<svg 80x40>dark" to PngSize.Pixels(40, 20)), rasterized)
    }

    @Test
    fun `a failed diagram is reported with its line and not written`() {
        rendering =
            Rendering(listOf(drawn("1x1"), Diagram.Failed("Syntax Error?", 7), Diagram.Failed("Timeout", null)), false)
        val export = chosen("SVG")!!
        assertEquals(
            listOf("Diagram 2 not exported: Line 7: Syntax Error?", "Diagram 3 not exported: Timeout"),
            export.write(file, "text", export.targets(file).first()),
        )
        assertEquals(listOf("diagram.svg"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `writing a PNG rasterises the light SVG at the size and writes it`() {
        rendering = Rendering(listOf(drawn("1x1"), Diagram.Failed("bad", null), drawn("1x1")), false)
        val export = chosen("PNG 200%")!!
        assertEquals(
            listOf("Diagram 2 not exported: bad"),
            export.write(file, "text", export.targets(file).first()),
        )
        assertEquals(
            listOf<Pair<String, PngSize>>(
                "<svg 1x1>light" to PngSize.Scale(2),
                "<svg 1x1>light" to PngSize.Scale(2),
            ),
            rasterized,
        )
        val image = assertNotNull(ImageIO.read(dir.resolve("diagram-3@2x.png").toFile()))
        assertEquals(8 to 6, image.width to image.height)
        assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
        assertTrue(dir.resolve("diagram@2x.png").exists())
        assertFalse(dir.resolve("diagram-2@2x.png").exists())
    }

    @Test
    fun `without a drawn diagram writing fails with the first failure`() {
        rendering = Rendering(listOf(Diagram.Failed("bad", 3), Diagram.Failed("worse", null)), false)
        val export = chosen("SVG")!!
        assertEquals(emptyList(), export.targets(file))
        val error = assertFailsWith<IOException> { export.write(file, "text", dir.resolve("diagram.svg")) }
        assertEquals("Line 3: bad", error.message)
        assertEquals(emptyList(), dir.listDirectoryEntries())

        rendering = Rendering(emptyList(), false)
        assertEquals(
            "No diagram to export",
            assertFailsWith<IOException> {
                chosen("SVG")!!.write(file, "text", dir.resolve("diagram.svg"))
            }.message,
        )
    }

    @Test
    fun `a label with a script is written as the engine escaped it, in a file named like a hostile file`() {
        val hostile = dir.resolve("a b\"c.puml")
        val source = "@startuml\nAlice -> Bob : <script>alert(1)</script>\n@enduml\n"
        val real =
            plantUmlExports(
                { text, line -> PlantUml.shared.render(text, line) },
                rasterizer,
                measurer,
                { _, _ -> null },
                { it.run() },
                dark = { false },
            ).first { it.label == "SVG" }
        val export = assertNotNull(real.choose(hostile, null, source).toCompletableFuture().get(60, TimeUnit.SECONDS))
        assertEquals(listOf(dir.resolve("a b\"c.svg")), export.targets(hostile))
        assertEquals(emptyList(), export.write(hostile, source, export.targets(hostile).first()))
        val svg = dir.resolve("a b\"c.svg").readText()
        assertTrue(svg.startsWith("<svg"), svg.take(200))
        assertFalse("<script" in svg, svg)
    }

    @Test
    fun `a name without extension or with several dots gets its suffix before the extension`() {
        rendering = Rendering(listOf(drawn("1x1"), drawn("1x1")), false)
        assertEquals(
            listOf("my.sequence.svg", "my.sequence-2.svg"),
            chosen("SVG", dir.resolve("my.sequence.puml"))!!.targets(dir.resolve("my.sequence.puml")).map { it.name },
        )
        assertEquals(
            listOf("plain.svg", "plain-2.svg"),
            chosen("SVG", dir.resolve("plain"))!!.targets(dir.resolve("plain")).map { it.name },
        )
    }

    @Test
    fun `a failing diagram of a PNG export is reported and the others are still written`() {
        rendering = Rendering(listOf(drawn("1x1"), drawn("2x2"), drawn("4x4"), drawn("1x1")), false)
        val export = chosen("PNG 100%")!!
        assertEquals(
            listOf("Diagram 2 not exported: Too large", "Diagram 3 not exported: I/O error"),
            export.write(file, "text", export.targets(file).first()),
        )
        assertEquals(listOf("diagram-4.png", "diagram.png"), dir.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `when every diagram fails to be written the export fails with the first reason`() {
        rendering = Rendering(listOf(drawn("2x2"), drawn("3x3")), false)
        val export = chosen("PNG 100%")!!
        assertEquals(
            "Too large",
            assertFailsWith<IOException> { export.write(file, "text", export.targets(file).first()) }.message,
        )
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun `choosing an export that is already bound completes with itself`() {
        rendering = Rendering(listOf(drawn("1x1")), false)
        val export = chosen("SVG")!!
        assertSame(export, export.choose(file, null, "text").toCompletableFuture().get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `the target is the first drawn diagram's file, also when the first diagram failed`() {
        rendering = Rendering(listOf(Diagram.Failed("bad", 1), drawn("100x50")), false)
        assertEquals(dir.resolve("diagram-2.svg"), chosen("SVG")!!.target(file))
        answer = 50 to 25
        assertEquals(dir.resolve("diagram-2-50x25.png"), chosen("PNG Custom…")!!.target(file))
    }
}
