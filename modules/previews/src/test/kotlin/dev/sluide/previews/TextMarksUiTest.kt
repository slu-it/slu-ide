package dev.sluide.previews

import dev.sluide.previews.keyPress
import javafx.concurrent.Worker
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.paint.Color
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TextMarks] and [DocumentZoom] on a real web view: the marks are JavaScript over the page's DOM and layout, which
 * only a WebView has. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TextMarksUiTest {
    private lateinit var webView: WebView
    private lateinit var marks: TextMarks
    private var userScrolls = 0

    @Start
    fun start(stage: Stage) {
        webView = WebView()
        marks = TextMarks(webView.engine) { webView.zoom }
        marks.onUserScroll = { userScrolls++ }
        stage.scene = Scene(webView, 600.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(10, TimeUnit.SECONDS)

    private fun script(js: String): Any? = fx { webView.engine.executeScript(js) }

    private fun page(): String {
        val filler =
            (0 until 60).joinToString("") {
                """<p><span data-o="${FILLER + it * 20}">Paragraph number ${"%02d".format(it)}</span></p>"""
            }
        return """<html><head><style>
            body { margin: 0; font: 16px sans-serif; background: #fff }
            p { margin: 20px 8px }
            ::highlight(mark-word) { background-color: #f00; color: #f00 }
            </style><script>${TextMarks.SCRIPT}</script></head><body>
            <p><span data-o="0">Hello </span><span data-o="6">brave </span><span data-o="12">world</span></p>
            <p style="width: 150px"><span data-o="499"></span><span data-o="500">$LONG</span></p>
            <p><span data-o="1000">abc</span></p>
            $filler</body></html>
            """
    }

    private fun load() {
        userScrolls = 0
        script("window.__old = 1")
        fx { webView.engine.loadContent(page()) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED } &&
                script("typeof window.__old") == "undefined" &&
                script("typeof sluide") == "object"
        }
    }

    /** Waits for a few frames, so that the scroll events of what the test did have been delivered. */
    private fun settle() {
        script("window.__frames = 0; (function f() { if (++window.__frames < 5) requestAnimationFrame(f) })()")
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { script("window.__frames") == 5 }
    }

    private fun highlighted(name: String) =
        script(
            "(CSS.highlights.get('mark-$name') ? Array.from(CSS.highlights.get('mark-$name')).map(String).join('|') : null)",
        )

    private fun fillerTop(index: Int) =
        (
            script(
                "document.querySelector('[data-o=\"${FILLER + index * 20}\"]').getBoundingClientRect().top",
            ) as Number
        ).toDouble()

    @Test
    fun `marks a range over three spans with partial first and last, and clears it`() {
        load()
        fx { marks.mark("sentence", 3, 14) }
        assertEquals("lo brave wo", highlighted("sentence"))
        fx { marks.clear("sentence") }
        assertNull(highlighted("sentence"))
    }

    @Test
    fun `marks of different names coexist and a name replaces its previous range`() {
        load()
        fx {
            marks.mark("sentence", 0, 17)
            marks.mark("word", 6, 11)
        }
        assertEquals("Hello brave world", highlighted("sentence"))
        assertEquals("brave", highlighted("word"))
        fx { marks.mark("word", 12, 17) }
        assertEquals("world", highlighted("word"))
        assertEquals("Hello brave world", highlighted("sentence"))
    }

    @Test
    fun `a range beyond the text of a shorter span ends at the span`() {
        load()
        fx { marks.mark("word", 1000, 1010) }
        assertEquals("abc", highlighted("word"))
        fx { marks.mark("word", 1001, 1002) }
        assertEquals("b", highlighted("word"))
    }

    @Test
    fun `a mark is painted with the highlight style of the page`() {
        load()
        fx { marks.mark("word", 6, 11) }
        val left = (script("document.querySelector('[data-o=\"6\"]').getBoundingClientRect().left") as Number).toInt()
        val top = (script("document.querySelector('[data-o=\"6\"]').getBoundingClientRect().top") as Number).toInt()

        fun pixel(
            x: Int,
            y: Int,
        ) = fx { webView.snapshot(null, null).pixelReader.getColor(x, y) }
        // Painting follows the script by a frame or more
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { pixel(left + 1, top + 3).green < 0.2 }
        assertTrue(pixel(left + 1, top + 3).red > 0.9)
        assertTrue(pixel(2, 2).green > 0.9)
    }

    /** The pixel a little inside the box of the span with `data-o` [offset], taken from a snapshot after the page settled. */
    private fun pixelOfSpan(offset: Int): Color {
        val left =
            (
                script(
                    "document.querySelector('[data-o=\"$offset\"]').getBoundingClientRect().left",
                ) as Number
            ).toInt()
        val top =
            (
                script(
                    "document.querySelector('[data-o=\"$offset\"]').getBoundingClientRect().top",
                ) as Number
            ).toInt()
        settle()
        WaitForAsyncUtils.waitForFxEvents()
        return fx { webView.snapshot(null, null).pixelReader.getColor(left + 1, top + 3) }
    }

    private fun assertPainted(offset: Int) =
        assertTrue(
            pixelOfSpan(offset).let {
                it.red > 0.9 && it.green < 0.2
            },
            "span $offset is highlighted",
        )

    private fun assertBackground(offset: Int) =
        assertTrue(
            // Not red: the pixel may be one of the dark, antialiased glyph
            pixelOfSpan(offset).let { !(it.red > 0.9 && it.green < 0.2) },
            "span $offset is back to the page background",
        )

    @Test
    fun `the script in the head observes the document only after it was parsed, and marks still work`() {
        script("window.__old = 1")
        val html =
            """<html><head><script>window.__obs = 0; (function (O) {
                window.MutationObserver = function (cb) { window.__obs++; return new O(cb); };
            })(window.MutationObserver);</script><script>${TextMarks.SCRIPT}</script></head><body>
            <p><span data-o="0">Hello</span></p>
            <script>window.__obsAtBody = window.__obs</script></body></html>"""
        fx { webView.engine.loadContent(html) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED } &&
                script("typeof window.__old") == "undefined" &&
                script("window.__obs") == 1
        }
        // A recorder over the whole body parse is what a huge book would pay for.
        assertEquals(0, script("window.__obsAtBody"))
        fx { marks.mark("word", 1, 4) }
        assertEquals("ell", highlighted("word"))
    }

    @Test
    fun `a replaced or cleared mark is repainted without the old highlight`() {
        load()
        fx { marks.mark("word", 0, 5) }
        assertPainted(0)
        fx { marks.mark("word", 6, 11) }
        assertPainted(6)
        assertBackground(0)
        fx { marks.clear("word") }
        assertBackground(6)
    }

    @Test
    fun `a mark across two paragraphs is repainted when replaced by one elsewhere`() {
        load()
        fx { marks.mark("word", 1000, FILLER + 5) }
        assertPainted(1000)
        assertPainted(FILLER)
        fx { marks.mark("word", 6, 11) }
        assertPainted(6)
        assertBackground(1000)
        assertBackground(FILLER)
    }

    @Test
    fun `many quick mark changes leave only the last one painted`() {
        load()
        fx { marks.mark("word", 0, 5) }
        assertPainted(0)
        fx {
            marks.mark("word", 6, 11)
            marks.mark("word", 12, 17)
        }
        assertPainted(12)
        assertBackground(0)
        assertBackground(6)
    }

    @Test
    fun `offsetAt finds the char under a point`() {
        load()
        val left =
            (
                script(
                    "document.querySelector('[data-o=\"6\"]').getBoundingClientRect().left",
                ) as Number
            ).toDouble()
        val top = (script("document.querySelector('[data-o=\"6\"]').getBoundingClientRect().top") as Number).toDouble()
        assertEquals(6, fx { marks.offsetAt(left + 1, top + 5) })
        val further = fx { marks.offsetAt(left + 1 + 3 * 8.0, top + 5) }
        assertTrue(further in 8..10, "offset further in the word is $further")
    }

    @Test
    fun `offsetAt divides the point by the zoom of the web view`() {
        load()
        fx { webView.zoom = 1.5 }
        settle()
        val left =
            (
                script(
                    "document.querySelector('[data-o=\"6\"]').getBoundingClientRect().left",
                ) as Number
            ).toDouble()
        val top = (script("document.querySelector('[data-o=\"6\"]').getBoundingClientRect().top") as Number).toDouble()
        assertEquals(6, fx { marks.offsetAt((left + 1) * 1.5, (top + 5) * 1.5) })
    }

    @Test
    fun `a scroll caused by a zoom is not a user scroll, a wheel still is`() {
        load()
        script("window.scrollBy(0, ${fillerTop(30)})")
        settle()
        userScrolls = 0
        script("window.__scrolls = 0; window.addEventListener('scroll', function () { window.__scrolls++ })")
        fx { webView.zoom = 1.5 }
        settle()
        assertTrue((script("window.__scrolls") as Number).toInt() > 0, "the zoom scrolled the page")
        assertEquals(0, userScrolls)
        script("window.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 }))")
        assertEquals(1, userScrolls)
    }

    @Test
    fun `topOffset of a multi-line span cut by the top edge is the start of its first visible line`() {
        load()
        val spanTop =
            (
                script(
                    "document.querySelector('[data-o=\"500\"]').getBoundingClientRect().top",
                ) as Number
            ).toDouble()
        val left =
            (
                script(
                    "document.querySelector('[data-o=\"500\"]').getBoundingClientRect().left",
                ) as Number
            ).toDouble()
        val lineHeight =
            (
                script(
                    "document.querySelector('[data-o=\"500\"]').getClientRects()[0].height",
                ) as Number
            ).toDouble()
        // The first line is out of sight, the second one cut
        script("window.scrollBy(0, ${spanTop + lineHeight * 1.5})")
        val secondLine = fx { marks.offsetAt(left + 1, lineHeight * 0.4) }
        assertTrue(secondLine != null && secondLine > 500, "second line starts at $secondLine")
        assertEquals(secondLine, fx { marks.topOffset() })
    }

    @Test
    fun `topOffset skips a hidden span right below the top edge`() {
        load()
        // Hidden but with text, so the index keeps it; it sits between visible spans
        script(
            "document.querySelector('[data-o=\"1000\"]').insertAdjacentHTML('afterend', '<span data-o=\"1005\" style=\"display: none\">hidden</span>')",
        )
        val above =
            (
                script(
                    "document.querySelector('[data-o=\"1000\"]').getBoundingClientRect().bottom",
                ) as Number
            ).toDouble()
        script("window.scrollBy(0, ${above + 1})")
        assertEquals(FILLER, fx { marks.topOffset() })
    }

    @Test
    fun `marks follow spans the page replaced`() {
        load()
        fx { marks.mark("word", 0, 5) }
        script("document.body.innerHTML = '<p><span data-o=\"0\">New text</span></p>'")
        settle()
        fx { marks.mark("word", 0, 3) }
        assertEquals("New", highlighted("word"))
    }

    @Test
    fun `topOffset follows the scroll position`() {
        load()
        assertEquals(0, fx { marks.topOffset() })
        script("window.scrollBy(0, ${fillerTop(10) - 5})")
        assertEquals(FILLER + 10 * 20, fx { marks.topOffset() })
    }

    @Test
    fun `reveal scrolls a far range into view without reporting a user scroll`() {
        load()
        val offset = FILLER + 45 * 20
        assertFalse(fx { marks.isVisible(offset, offset + 5) })
        fx { marks.reveal(offset) }
        settle()
        assertTrue(fx { marks.isVisible(offset, offset + 5) })
        assertEquals(0, userScrolls)

        // Already comfortably in view: stays
        val y = script("window.scrollY")
        fx { marks.reveal(offset) }
        assertEquals(y, script("window.scrollY"))
        assertEquals(0, userScrolls)
    }

    @Test
    fun `scrollToTop puts the line at the top without a user scroll, a wheel right after is reported`() {
        load()
        val offset = FILLER + 45 * 20
        fx { marks.scrollToTop(offset) }
        settle()
        assertTrue(fx { marks.topOffset() } in offset..(offset + 2), "top ${fx { marks.topOffset() }}")
        assertEquals(0, userScrolls)
        script("window.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 }))")
        assertEquals(1, userScrolls)
    }

    @Test
    fun `a scrollToTop and a reveal in the same frame are both told apart from the user`() {
        load()
        fx {
            marks.scrollToTop(FILLER + 45 * 20)
            marks.reveal(FILLER + 5 * 20)
        }
        settle()
        assertEquals(0, userScrolls)
        // The reveal came last: the second scroll of scrollToTop does not take the page away from it.
        assertTrue(fx { marks.isVisible(FILLER + 5 * 20, FILLER + 5 * 20 + 5) })
    }

    @Test
    fun `a wheel or a scroll by the user is reported`() {
        load()
        script("window.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 }))")
        assertEquals(1, userScrolls)
        // The bridge is installed again for the next page
        load()
        script("window.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 }))")
        assertEquals(1, userScrolls)
        userScrolls = 0
        script("window.scrollBy(0, 50)")
        settle()
        assertTrue(userScrolls > 0)
    }

    @Test
    fun `before a page is loaded every call does nothing`() {
        val fresh = fx { TextMarks(WebEngine()) }
        fx { fresh.mark("word", 0, 3) }
        fx { fresh.reveal(0) }
        assertFalse(fx { fresh.isVisible(0, 3) })
        assertNull(fx { fresh.topOffset() })
        assertNull(fx { fresh.offsetAt(1.0, 1.0) })
    }

    @Test
    fun `DocumentZoom zooms the web view with Shortcut and Plus`() {
        load()
        val zoom = fx { DocumentZoom(webView).also { it.install() } }
        assertEquals(1.0, fx { webView.zoom })
        fx { Event.fireEvent(webView, keyPress(KeyCode.PLUS)) }
        assertEquals(1.25, fx { webView.zoom })
        assertEquals("125%", fx { (zoom.node.lookup(".zoom-level") as Label).text })
    }

    private companion object {
        const val FILLER = 2000
        const val LONG = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor"
    }
}
