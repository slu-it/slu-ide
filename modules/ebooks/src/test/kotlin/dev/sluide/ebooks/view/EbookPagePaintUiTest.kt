package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import dev.sluide.ebooks.BookPageBuilder
import dev.sluide.ebooks.EpubReader
import dev.sluide.ebooks.EpubResult
import dev.sluide.ebooks.TestChapter
import dev.sluide.ebooks.TestEpub
import dev.sluide.previews.TextMarks
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.web.WebView
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the ebook page shows, by its pixels on a real WebView: the marks' highlight rules reach the screen (dark and
 * light), a book's own `class="mark-word"` paints nothing, and the page's Content-Security-Policy is enforced by the
 * engine. A unit test cannot see either: CSS that matches no node, or a policy WebKit ignores, passes every string
 * check (ADR 0165).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EbookPagePaintUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var webView: WebView
    private lateinit var marks: TextMarks

    @Start
    fun start(stage: Stage) {
        webView = WebView()
        marks = TextMarks(webView.engine) { webView.zoom }
        stage.scene = Scene(webView, 600.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(10, TimeUnit.SECONDS)

    private fun script(js: String): Any? = fx { webView.engine.executeScript(js) }

    private fun build(
        bodies: List<String>,
        dark: Boolean,
        ownStyle: Boolean = false,
    ): BookPage {
        val chapters = bodies.mapIndexed { i, body -> TestChapter("c$i.xhtml", body, title = "T$i") }
        val file = TestEpub(chapters).write(dir, "book${System.nanoTime()}.epub")
        val book = assertIs(EpubReader.read(file)).book
        return BookPageBuilder.build(book, ownStyle, dark, PageBridge.SCRIPT)
    }

    private fun <T> assertIs(result: T): EpubResult.Book = result as EpubResult.Book

    private fun load(html: String) {
        script("window.__old = 1")
        fx { webView.engine.loadContent(html) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED } &&
                script("typeof window.__old") == "undefined" &&
                script("typeof sluide") == "object"
        }
        settle()
    }

    /** Waits for a few frames, so that painting caught up with what the test did. */
    private fun settle() {
        script("window.__frames = 0; (function f() { if (++window.__frames < 5) requestAnimationFrame(f) })()")
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { script("window.__frames") == 5 }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private class Box(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private fun boxOf(offset: Int): Box {
        val rect = "document.querySelector('[data-o=\"$offset\"]').getBoundingClientRect()"

        fun side(name: String) = (script("$rect.$name") as Number).toInt()
        return Box(side("left"), side("top"), side("right"), side("bottom"))
    }

    private fun near(
        a: Color,
        b: Color,
    ) = abs(a.red - b.red) < 0.06 && abs(a.green - b.green) < 0.06 && abs(a.blue - b.blue) < 0.06

    /** The pixels of a row inside the top of the span box, above the glyphs, from a snapshot after the page settled. */
    private fun topRow(box: Box): List<Color> {
        val image = fx { webView.snapshot(null, null) }
        val y = box.top + 1
        return (box.left + 1 until minOf(box.right - 1, box.left + 40)).map { image.pixelReader.getColor(it, y) }
    }

    private fun assertPaints(
        offset: Int,
        expected: Color,
        what: String,
    ) {
        val box = boxOf(offset)
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { topRow(box).count { near(it, expected) } >= 10 }
        assertTrue(topRow(box).count { near(it, expected) } >= 10, what)
    }

    private fun paragraphs(vararg words: String) = words.joinToString("") { "<p>$it</p>" }

    private fun rgb(
        r: Int,
        g: Int,
        b: Int,
    ) = Color.rgb(r, g, b)

    @Test
    fun `sentence, word and search marks paint their background, light and dark`() {
        val texts = listOf("sentence line here", "word line here", "search line here", "plain line here")
        for (dark in listOf(false, true)) {
            val page = build(listOf(paragraphs(*texts.toTypedArray())), dark)
            load(page.html)
            val offsets = texts.map { page.text.indexOf(it) }
            val background = if (dark) rgb(30, 31, 34) else rgb(255, 255, 255)
            val expected =
                if (dark) {
                    listOf(rgb(51, 62, 89), rgb(86, 114, 171), rgb(150, 128, 31))
                } else {
                    listOf(rgb(255, 241, 201), rgb(255, 196, 64), rgb(255, 241, 118))
                }
            val names = listOf("sentence", "word", "search")
            for (i in names.indices) {
                // Unmarked first: the row has no such colour, so the check below sees the mark and nothing else.
                assertEquals(0, topRow(boxOf(offsets[i])).count { near(it, expected[i]) }, "${names[i]} before")
                fx { marks.mark(names[i], offsets[i], offsets[i] + texts[i].length) }
                assertPaints(offsets[i], expected[i], "$dark ${names[i]}")
            }
            assertEquals(0, topRow(boxOf(offsets[3])).count { !near(it, background) }, "the unmarked line is plain")
        }
    }

    @Test
    fun `the start mark paints its underline below the text, light and dark`() {
        val text = "start line here"
        for (dark in listOf(false, true)) {
            val page = build(listOf(paragraphs(text)), dark)
            load(page.html)
            val offset = page.text.indexOf(text)
            val underline = if (dark) rgb(106, 165, 255) else rgb(47, 125, 225)

            fun blueRows(): Int {
                val box = boxOf(offset)
                val image = fx { webView.snapshot(null, null) }
                // The underline lies below the baseline: only the lower half of the box and a little under it.
                val from = (box.top + box.bottom) / 2
                return (from..box.bottom + 3).count { y ->
                    (box.left + 1 until box.right - 1).count {
                        image.pixelReader.getColor(it, y).let { c ->
                            near(c, underline)
                        }
                    } >=
                        10
                }
            }
            assertEquals(0, blueRows(), "$dark before")
            fx { marks.mark("start", offset, offset + text.length) }
            settle()
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { blueRows() > 0 }
            assertTrue(blueRows() > 0, "$dark the underline is drawn")
        }
    }

    @Test
    fun `a book's own mark class paints nothing under the book style`() {
        for (dark in listOf(false, true)) {
            val text = "classy line here"
            val page =
                build(
                    listOf("<p class=\"mark-word\">$text</p><p class=\"mark-sentence\">second line here</p>"),
                    dark,
                    true,
                )
            load(page.html)
            val background = if (dark) rgb(30, 31, 34) else rgb(255, 255, 255)
            for (word in listOf(text, "second line here")) {
                val offset = page.text.indexOf(word)
                val row = topRow(boxOf(offset))
                assertTrue(row.isNotEmpty() && row.all { near(it, background) }, "$dark $word has no highlight")
            }
        }
    }

    /** An inline handler and a foreign script, each leaving its own mark, so that one cannot hide the other. */
    private fun injected(html: String) =
        html.replace(
            "</body>",
            "<img id=\"injected\" src=\"x\" onerror=\"window.__h=1;document.title='pwned'\">" +
                "<script>window.__s=1;document.title='pwned2'</script></body>",
        )

    @Test
    fun `the engine refuses inline handlers and foreign scripts of the page but runs its own`() {
        val page = build(listOf("<p>Some text</p>"), dark = false)
        val html = injected(page.html)

        // Without the policy the same injection works (handler and script both run), so the check below is not vacuous.
        load(html.replace(Regex("<meta http-equiv=\"Content-Security-Policy\"[^>]*>"), ""))
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { script("window.__h") == 1 && script("window.__s") == 1 }

        load(html)
        assertEquals("object", script("typeof window.sluide.marks"))
        // The image has finished failing, so its handler would have run by now.
        WaitForAsyncUtils.waitFor(
            10,
            TimeUnit.SECONDS,
        ) { script("document.getElementById('injected').complete") == true }
        settle()
        assertEquals("undefined", script("typeof window.__h"))
        assertEquals("undefined", script("typeof window.__s"))
        assertEquals("Test Book", script("document.title"))
    }
}
