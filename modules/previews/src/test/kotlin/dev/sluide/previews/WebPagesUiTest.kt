package dev.sluide.previews

import javafx.concurrent.Worker
import javafx.scene.Scene
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
}
