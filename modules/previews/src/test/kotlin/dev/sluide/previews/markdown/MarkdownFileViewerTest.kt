package dev.sluide.previews.markdown

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.TokenStyle
import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.plantuml.PlantUmlHtml
import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MarkdownFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer = MarkdownFileViewer()

    private fun file(name: String) = dir.resolve(name).apply { writeText("# Title\n") }

    @Test
    fun `opens Markdown text files only`() {
        val names = listOf("README.md", "notes.markdown", "LEGACY.MD", "docs.mdx", "notes.txt", "md")
        assertEquals(listOf("README.md", "notes.markdown", "LEGACY.MD"), names.filter { viewer.canOpen(file(it)) })
        assertFalse(viewer.canOpen(dir.resolve("missing.md")))
        assertFalse(viewer.canOpen(dir.resolve("binary.md").apply { writeBytes(byteArrayOf(0x61, 0x00)) }))
    }

    @Test
    fun `is chosen for Markdown files, before the default text viewer`() {
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(fallback, viewer), fallback)

        assertSame(viewer, viewers.viewerFor(file("README.md")))
    }

    @Test
    fun `diffs are highlighted like the source`() {
        assertSame(viewer.highlighter, viewer.sourceHighlighter)
        val spans = viewer.sourceHighlighter.highlight("# Title\n")
        assertEquals(TokenStyle.KEYWORD, spans.single { it.start <= 1 && 1 < it.end }.style)
    }

    @Test
    fun `each view's preview draws PlantUML fences and renders again when a drawing arrives, until detached`() {
        assertNotSame(viewer.newPreview(), viewer.newPreview())
        val requests = mutableListOf<Pair<String, CompletableFuture<Rendering>>>()
        val fxQueue = ArrayDeque<Runnable>()
        var rerenders = 0

        fun preview() =
            MarkdownPreview(
                { source, _ -> CompletableFuture<Rendering>().also { requests += source to it } },
                fxQueue::addLast,
            ).apply { attach({ rerenders++ }) { null } }

        fun arrive() {
            requests.last().second.complete(Rendering(listOf(Diagram.Drawn("<svg/>", "<svg/>")), false))
            while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()
        }
        val markdown = "# Title\n\n```plantuml\nA -> B\n```\n"
        val preview = preview()

        val drawing = Jsoup.parse(preview.page(markdown, dir, dark = false))
        assertEquals(PlantUmlHtml.DRAWING, drawing.selectFirst("p.diagram-note")?.text())
        arrive()
        assertEquals(1, rerenders)
        val drawn = Jsoup.parse(preview.page(markdown, dir, dark = false))
        assertEquals(1, drawn.select("img.plantuml-light").size)
        assertEquals(1, requests.size)

        val detached = preview()
        detached.page(markdown, dir, dark = false)
        detached.detach()
        arrive()
        assertEquals(1, rerenders, "no rerender after detach")
    }

    @Test
    fun `a fence moved by an edit above it is not drawn again, its error names its new line`() {
        val requests = mutableListOf<String>()
        val fxQueue = ArrayDeque<Runnable>()
        // Fails on the line `bad`, numbered from the first line as the engine numbers it.
        val preview =
            MarkdownPreview({ source, firstLine ->
                requests += source
                val failed = Diagram.Failed("Syntax?", firstLine + source.lines().indexOf("bad"))
                CompletableFuture.completedFuture(Rendering(listOf(failed), false))
            }, fxQueue::addLast).apply { attach({}) { null } }

        /** The error shown for [markdown] once its drawings are there. */
        fun error(markdown: String): String? {
            preview.page(markdown, dir, dark = false)
            while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()
            return Jsoup.parse(preview.page(markdown, dir, dark = false)).selectFirst("pre.plantuml-error")?.text()
        }

        assertEquals("Line 4: Syntax?", error("# Title\n\n```plantuml\nbad\n```\n"))
        assertEquals("Line 5: Syntax?", error("# Title\n\nMore\n```plantuml\nbad\n```\n"))
        assertEquals(1, requests.size, "drawn once")
    }
}
