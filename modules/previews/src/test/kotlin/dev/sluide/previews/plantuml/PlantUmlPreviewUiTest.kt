package dev.sluide.previews.plantuml

import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewMode
import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** PlantUML files with a real WebView and the real engine. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PlantUmlPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    /** How often the views asked the engine to draw. */
    private val renders = AtomicInteger()

    private val viewer =
        PlantUmlFileViewer { source, firstLine ->
            renders.incrementAndGet()
            PlantUml.shared.render(source, firstLine)
        }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun open(
        file: Path,
        with: PlantUmlFileViewer = viewer,
    ): PreviewFileView = fx { with.open(file) as PreviewFileView }

    private fun show(
        robot: FxRobot,
        source: String,
        with: PlantUmlFileViewer = viewer,
    ): PreviewFileView {
        val view = open(dir.resolve("diagram.puml").apply { writeText(source) }, with)
        robot.interact { container.children.setAll(view.node) }
        view.awaitLoaded()
        return view
    }

    private fun PreviewFileView.script(code: String): Any? = fx { webView.engine.executeScript(code) }

    private fun PreviewFileView.number(code: String) = (script(code) as Number).toDouble()

    /**
     * Waits until the view's first page is loaded: "Drawing the diagram…" loads at once, without the engine. Within a
     * mutation run's time limit (35 s more than a test takes), so that a view that loads nothing fails, not times out.
     */
    private fun PreviewFileView.awaitLoaded() =
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED }
        }

    /**
     * Waits until the page is loaded and [condition] holds in it; the first drawing starts PlantUML, which is slow. Also
     * within a mutation run's time limit, so that a drawing never shown fails.
     */
    private fun PreviewFileView.awaitTrue(condition: String) =
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED } && script(condition) == true
        }

    /** Waits until the page shows [count] drawn diagrams of the theme's variant, loaded. */
    private fun PreviewFileView.awaitDiagrams(count: Int) =
        awaitTrue(
            "(function () { var all = document.querySelectorAll('.plantuml-diagram img');" +
                " var shown = Array.prototype.filter.call(all," +
                " function (img) { return getComputedStyle(img).display !== 'none'; });" +
                " return shown.length === $count" +
                " && shown.every(function (img) { return img.complete && img.naturalWidth > 0; }); })()",
        )

    /** The number of pixels inside the shown variant of the diagram [index] that [ink] counts, in a snapshot. */
    private fun PreviewFileView.inkIn(
        index: Int,
        ink: (Double) -> Boolean,
    ): Int {
        val variant = if (script("document.body.classList.contains('dark')") == true) "dark" else "light"

        fun side(name: String) =
            number("document.querySelectorAll('img.plantuml-$variant')[$index].getBoundingClientRect().$name").toInt()
        val (left, top, right, bottom) = listOf(side("left"), side("top"), side("right"), side("bottom"))
        val image = fx { webView.snapshot(null, null) }
        var count = 0
        for (x in left + 1 until minOf(right, image.width.toInt()) step 2) {
            for (y in top + 1 until minOf(bottom, image.height.toInt()) step 2) {
                if (ink(image.pixelReader.getColor(x, y).brightness)) count++
            }
        }
        return count
    }

    private val sequence = "@startuml\nAlice -> Bob : hello\nBob --> Alice : hi\n@enduml\n"

    @Test
    fun `a PlantUML file opens in Split and paints its diagram, the dark variant in the dark theme`(robot: FxRobot) {
        val view = show(robot, sequence)

        assertEquals(PreviewMode.SPLIT, fx { view.mode.get() })
        view.awaitDiagrams(1)
        assertTrue(view.inkIn(0) { it < 0.4 } > 20, "dark lines and text on the light variant")
        assertEquals(1, renders.get())

        view.script("window.loadedBefore = true")
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")

        assertEquals("none", view.script("getComputedStyle(document.querySelector('img.plantuml-light')).display"))
        assertEquals("block", view.script("getComputedStyle(document.querySelector('img.plantuml-dark')).display"))
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.inkIn(0) { it < 0.2 } > 100 }
        assertTrue(view.inkIn(0) { it > 0.6 } > 20, "light lines and text on the dark variant")
        assertEquals(true, view.script("window.loadedBefore === true"), "the page was not loaded again")
        assertEquals(1, renders.get(), "nothing drawn again")
    }

    @Test
    fun `the blocks of a file are stacked and fitted together, a click shows them at 100 percent`(robot: FxRobot) {
        fun block(name: String) =
            "@startuml\n" + (1..16).joinToString("\n") { "$name -> Other : message $it" } + "\n@enduml\n"
        val view = show(robot, block("First") + "\n" + block("Second"))
        view.awaitDiagrams(2)
        val zoom = view.zoom
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { zoom.fit } }

        val viewHeight = view.number("window.innerHeight")

        /** The shown [variant]'s images: stacked with a gap and, as one, fitted to the view at the zoom's scale. */
        fun assertFitted(variant: String) {
            val image = "document.querySelectorAll('img.plantuml-$variant')"
            // At 100%, as preview.js remembers it: WebKit reports a sized SVG image's natural size as its size.
            val natural = (0..1).map { view.number("parseFloat($image[$it].getAttribute('data-height'))") }
            assertTrue(natural.sum() > viewHeight, "taller than the view at 100%: $natural, $viewHeight")
            val gap = "$image[1].getBoundingClientRect().top - $image[0].getBoundingClientRect().bottom"
            assertTrue(view.number(gap) >= 16, "a gap")
            assertTrue(view.number("document.documentElement.scrollHeight") <= viewHeight, "both fit")
            // Only the shown variant counts: with the hidden one the stack would fill half the view.
            assertEquals(viewHeight, view.number("document.body.getBoundingClientRect().height"), 2.0)
            val scale = fx { zoom.scale }
            for (i in 0..1) {
                assertEquals(scale, view.number("$image[$i].getBoundingClientRect().height") / natural[i], 0.01)
            }
        }
        assertFitted("light")

        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")
        assertFitted("dark")

        val image = "document.querySelectorAll('img.plantuml-dark')"
        view.script("$image[1].dispatchEvent(new MouseEvent('click', { bubbles: true }))")

        assertEquals(false, fx { zoom.fit })
        assertEquals(1.0, fx { zoom.scale })
        val height = view.number("$image[1].getBoundingClientRect().height")
        assertEquals(view.number("parseFloat($image[1].getAttribute('data-height'))"), height, 1.0)
        assertTrue(view.number("document.documentElement.scrollHeight") > viewHeight, "the page scrolls")
    }

    @Test
    fun `while a saved text is drawn the diagram shown stays, zoomed as it was, until the new one is there`(
        robot: FxRobot,
    ) {
        // The second drawing waits until the test hands it over.
        val held = CompletableFuture<Rendering>()
        val holding =
            PlantUmlFileViewer { source, firstLine ->
                if (renders.incrementAndGet() == 1) PlantUml.shared.render(source, firstLine) else held
            }
        val view = show(robot, sequence, holding)
        view.awaitDiagrams(1)
        val zoom = view.zoom
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { zoom.fit } }
        val image = "document.querySelector('img.plantuml-light')"
        view.script("$image.dispatchEvent(new MouseEvent('click', { bubbles: true }))")
        assertEquals(false, fx { zoom.fit })
        val shown = view.script("$image.src")
        view.script("window.loadedBefore = true")

        val later = "@startuml\nAlice -> Carol : bye\n@enduml\n"
        dir.resolve("diagram.puml").writeText(later)
        robot.interact { view.reload() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { renders.get() == 2 }
        // A page load started by the reload would swap the document only in a later pulse.
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(true, view.script("window.loadedBefore === true"), "the page shown was not loaded again")
        assertEquals(shown, view.script("$image.src"))
        assertEquals(false, fx { zoom.fit })

        held.complete(PlantUml.shared.render(later).get(30, TimeUnit.SECONDS))
        view.awaitTrue("window.loadedBefore !== true")
        view.awaitDiagrams(1)

        assertNotEquals(shown, view.script("$image.src"))
        assertEquals(false, fx { zoom.fit })
        assertEquals(1.0, fx { zoom.scale })
    }

    @Test
    fun `a syntax error shows PlantUML's message with the file's line in an error box, which a click does not zoom`(
        robot: FxRobot,
    ) {
        // The viewer as Spring creates it: the app's engine, results handed to the FX thread by Platform.runLater.
        val view =
            show(
                robot,
                "$sequence\n' a comment\n@startuml\nAlice -> Bob\nthis is no diagram\n@enduml\n",
                PlantUmlFileViewer(),
            )

        view.awaitDiagrams(1)
        view.awaitTrue("document.querySelector('pre.plantuml-error') !== null")

        val message = view.script("document.querySelector('pre.plantuml-error').textContent") as String
        assertTrue(message.startsWith("Line 9: "), message)
        val gap =
            "document.querySelector('pre.plantuml-error').getBoundingClientRect().top" +
                " - document.querySelector('.plantuml-diagram').getBoundingClientRect().bottom"
        assertEquals(16.0, view.number(gap), 0.5, "the gap between the diagram and the error box below it")
        val zoom = view.zoom
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { zoom.fit } }
        // Its text can be selected; the diagram above it zooms.
        view.script(
            "document.querySelector('pre.plantuml-error').dispatchEvent(new MouseEvent('click', { bubbles: true }))",
        )
        assertTrue(fx { zoom.fit })
        view.script(
            "document.querySelector('img.plantuml-light').dispatchEvent(new MouseEvent('click', { bubbles: true }))",
        )
        assertEquals(false, fx { zoom.fit })
        assertEquals(
            listOf("rgb(207, 34, 46)", "rgb(255, 235, 233)", "\"Syntax error\""),
            listOf("color", "backgroundColor").map {
                view.script("getComputedStyle(document.querySelector('pre.plantuml-error')).$it")
            } + view.script("getComputedStyle(document.querySelector('pre.plantuml-error'), '::before').content"),
            "the look of Mermaid's error box",
        )
    }

    @Test
    fun `a PlantUML file in Split gives the focus to its diagram`(robot: FxRobot) {
        val view = open(dir.resolve("diagram.puml").apply { writeText(sequence) })
        // Asked before it is shown: the split pane adds the web view to the scene only when it is skinned.
        robot.interact {
            view.focus()
            container.children.setAll(view.node)
        }
        view.awaitLoaded()

        assertSame(view.webView, fx { container.scene.focusOwner })
    }

    // Markdown files: their PlantUML fences are drawn with the same look and engine (PlantUmlFences); here for the
    // helpers above.

    /** Opens [markdown] as `notes.md` with [with], shows it and waits for its first page. */
    private fun showMarkdown(
        robot: FxRobot,
        markdown: String,
        with: MarkdownFileViewer,
    ): PreviewFileView {
        val view = fx { with.open(dir.resolve("notes.md").apply { writeText(markdown) }) as PreviewFileView }
        robot.interact { container.children.setAll(view.node) }
        view.awaitLoaded()
        return view
    }

    @Test
    fun `a Markdown file paints its PlantUML fence apart from the next block, the dark variant without a reload`(
        robot: FxRobot,
    ) {
        // The viewer as Spring creates it: the app's engine, results handed to the FX thread by Platform.runLater.
        val view =
            showMarkdown(
                robot,
                "# Notes\n\n```plantuml\nAlice -> Bob : hello\n```\nAfter the diagram\n",
                MarkdownFileViewer(),
            )

        view.awaitDiagrams(1)
        assertTrue(view.inkIn(0) { it < 0.4 } > 20, "dark lines and text on the light variant")
        val gap =
            "document.querySelector('div.plantuml + p').getBoundingClientRect().top" +
                " - document.querySelector('div.plantuml').getBoundingClientRect().bottom"
        assertEquals(16.0, view.number(gap), 0.5, "the gap between the diagram and the paragraph after it")

        view.script("window.loadedBefore = true")
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.inkIn(0) { it > 0.6 } > 20 }
        assertEquals("none", view.script("getComputedStyle(document.querySelector('img.plantuml-light')).display"))
        assertEquals(true, view.script("window.loadedBefore === true"), "the page was not loaded again")
    }

    @Test
    fun `a long Markdown file shown alone keeps its scroll position when a fence's drawing arrives`(robot: FxRobot) {
        val held = CompletableFuture<Rendering>()
        val markdown = MarkdownFileViewer { _, _ -> held }
        val paragraphs = (1..60).joinToString("") { "Paragraph $it\n\n" }
        val view = showMarkdown(robot, "$paragraphs```plantuml\nAlice -> Bob : hello\n```\n\n$paragraphs", markdown)
        robot.interact { view.mode.set(PreviewMode.PREVIEW) }
        val middle = view.number("document.documentElement.scrollHeight") / 2
        view.script("window.scrollTo(0, $middle)")
        val scrolled = view.number("window.scrollY")
        assertTrue(scrolled > 1000, "scrolled to the middle: $scrolled")
        view.script("window.loadedBefore = true")

        held.complete(PlantUml.shared.render("@startuml\nAlice -> Bob : hello\n@enduml\n").get(30, TimeUnit.SECONDS))
        view.awaitTrue("window.loadedBefore !== true")
        view.awaitDiagrams(1)

        assertEquals(scrolled, view.number("window.scrollY"), 1.0)
    }
}
