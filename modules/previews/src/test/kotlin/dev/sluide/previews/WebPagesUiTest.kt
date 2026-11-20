package dev.sluide.previews

import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [loadPage] on a real web view. The defect is the text WebKit receives (six U+FFFD for one emoji), so reading the
 * code points back from the DOM, the script and the computed style is the paint-level check here; it needs WebKit,
 * a unit test cannot see what `loadContent` does with the string. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class WebPagesUiTest {
    private lateinit var webView: WebView

    @Start
    fun start(stage: Stage) {
        webView = WebView()
        stage.scene = Scene(webView, 400.0, 300.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(10, TimeUnit.SECONDS)

    private fun script(js: String): Any? = fx { webView.engine.executeScript(js) }

    private fun codePoints(expression: String): String =
        script("Array.from($expression).map(c => c.codePointAt(0).toString(16)).join(' ')") as String

    @Test
    fun `astral characters in text, attributes, script and style reach WebKit intact`() {
        val page =
            """<html><head>
            <style>#a::before { content: "🚀" }</style>
            <script>window.x = '🚀'</script></head>
            <body><p id="a" title="🚀">x🚀y</p></body></html>"""
        script("window.__old = 1")
        fx { webView.engine.loadPage(page) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED } &&
                script("typeof window.__old") == "undefined"
        }

        assertEquals("78 1f680 79", codePoints("document.getElementById('a').textContent"), "text")
        assertEquals("1f680", codePoints("document.getElementById('a').title"), "attribute")
        assertEquals("1f680", codePoints("window.x"), "script")
        assertEquals(
            "22 1f680 22",
            codePoints("getComputedStyle(document.getElementById('a'), '::before').content"),
            "style",
        )
    }

    /**
     * Only JavaFX's WebKit on macOS mis-lays-out a text node that holds a colour emoji together with other text (the
     * rest of a heading vanishes); the Linux sandbox has no emoji font. Needs the manual macOS UI test workflow, so it
     * has not been seen red or green; without the span around the emoji it is expected to find no light pixel.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    fun `the text after an emoji in a heading is painted`() {
        val rocket = "🚀"
        val page =
            """<html><body style="margin:0;background:#101010">
            <h2 style="margin:8px;color:#f0f0f0;font:bold 32px sans-serif">$rocket Headline after rocket</h2>
            </body></html>"""
        script("window.__old = 1")
        fx { webView.engine.loadPage(page) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { webView.engine.loadWorker.state == Worker.State.SUCCEEDED } &&
                script("typeof window.__old") == "undefined"
        }
        // From the "H" of the last text node on: only the text after the emoji, whether or not the emoji has a span
        val rect =
            script(
                """(function () {
                  var walker = document.createTreeWalker(document.querySelector('h2'), NodeFilter.SHOW_TEXT);
                  var last; while (walker.nextNode()) last = walker.currentNode;
                  var r = document.createRange(); r.setStart(last, last.data.indexOf("H"));
                  r.setEnd(last, last.data.length);
                  var b = r.getBoundingClientRect();
                  return [b.left, b.top, b.right, b.bottom].map(Math.round).join(',');
                })()""",
            ) as String
        val (left, top, right, bottom) = rect.split(",").map { it.toInt() }
        // Painting follows the layout by a frame or more
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { lightPixels(left, top, right, bottom) > 50 }
        assertTrue(lightPixels(left, top, right, bottom) > 50, "the heading text after the emoji is painted")
    }

    private fun lightPixels(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Int {
        val image = fx { webView.snapshot(null, null) }
        val reader = image.pixelReader
        var count = 0
        for (y in top until minOf(bottom, image.height.toInt())) {
            for (x in left until minOf(right, image.width.toInt())) {
                if (reader.getColor(x, y).brightness > 0.6) count++
            }
        }
        return count
    }
}
