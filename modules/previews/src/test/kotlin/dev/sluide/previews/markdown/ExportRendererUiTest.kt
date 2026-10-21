package dev.sluide.previews.markdown

import javafx.stage.Stage
import org.jsoup.Jsoup
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.time.Duration
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The offscreen renderer of the HTML export with the bundled KaTeX and Mermaid. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ExportRendererUiTest {
    @Start
    @Suppress("UNUSED_PARAMETER")
    fun start(stage: Stage) {
        // Nothing is shown: the renderer works offscreen.
    }

    private companion object {
        const val FLOWCHART = "flowchart LR\n  Start --> Middle[A longer label] --> Stop\n"
        const val SEQUENCE = "sequenceDiagram\n  Alice->>Bob: Hello Bob\n  Bob-->>Alice: Hi Alice\n"

        // Drawn once for all tests.
        val rendering by lazy {
            WebViewExportRenderer().render(
                listOf(MathSource("x^2", display = false), MathSource("\\frac{a}{b}", display = true)),
                listOf(FLOWCHART, SEQUENCE, "flowchart LR\n  A -->"),
            )
        }
    }

    @Test
    fun `formulas are typeset by KaTeX`() {
        assertEquals(2, rendering.math.size)
        assertTrue(assertNotNull(rendering.math[0]).contains("class=\"katex\""), rendering.math[0])
        assertTrue(assertNotNull(rendering.math[1]).contains("katex-display"), rendering.math[1])
    }

    @Test
    fun `diagrams are drawn in both themes with sizes`() {
        for (diagram in rendering.diagrams.take(2)) {
            assertNull(diagram.error)
            for (svg in listOf(diagram.light, diagram.dark)) {
                val root = assertNotNull(Jsoup.parseBodyFragment(assertNotNull(svg)).selectFirst("svg"))
                val viewBox = root.attr("viewBox").split(' ').map { it.toDouble() }
                assertTrue(viewBox[2] > 0 && viewBox[3] > 0, root.attr("viewBox"))
                assertTrue(root.attr("style").contains("max-width"), root.attr("style"))
                val widths = root.select("rect[width]").map { it.attr("width").toDouble() }
                assertTrue(widths.isNotEmpty() && widths.all { it > 0 }, widths.toString())
            }
        }
        assertTrue(rendering.diagrams[0].dark != rendering.diagrams[0].light)
    }

    @Test
    fun `the ids of all drawings are unique in one page`() {
        val svgs =
            rendering.diagrams
                .take(2)
                .flatMap { listOf(it.light, it.dark) }
                .joinToString("\n")
        val ids = Jsoup.parseBodyFragment(svgs).select("[id]").map { it.id() }
        val repeated = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(repeated.isEmpty(), repeated.toString())
        // Markers too, which the arrows reference.
        assertTrue(ids.any { "arrowhead" in it || "pointEnd" in it }, ids.toString())
    }

    @Test
    fun `a diagram that does not parse gets Mermaid's error`() {
        val failed = rendering.diagrams[2]
        assertFalse(failed.failed)
        assertNull(failed.light)
        assertNull(failed.dark)
        assertTrue(!failed.error.isNullOrBlank())
    }

    @Test
    fun `nothing to render returns at once`() {
        val empty = WebViewExportRenderer().render(emptyList(), emptyList())
        assertTrue(empty.math.isEmpty() && empty.diagrams.isEmpty())
    }

    @Test
    fun `what is not done in time is missing`() {
        val late = WebViewExportRenderer(Duration.ofMillis(1)).render(listOf(MathSource("x", false)), listOf(FLOWCHART))
        assertEquals(listOf(null), late.math)
        val diagram = late.diagrams.single()
        assertEquals("Timed out", diagram.error)
        assertTrue(diagram.failed)
        assertNull(diagram.light)
    }

    @Test
    fun `rendering on the FX thread is refused`() {
        val failure =
            assertFailsWith<ExecutionException> {
                WaitForAsyncUtils
                    .asyncFx<ExportRendering> {
                        WebViewExportRenderer().render(emptyList(), listOf(FLOWCHART))
                    }.get()
            }
        assertIs<IllegalStateException>(failure.cause)
    }
}
