package dev.sluide.previews.markdown

import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The colour scheme button of a standalone page, in a real web engine. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class StandalonePageUiTest {
    @TempDir
    lateinit var dir: Path

    @Start
    @Suppress("UNUSED_PARAMETER")
    fun start(stage: Stage) {
        // Nothing is shown: the page is loaded offscreen.
    }

    private fun load(): WebEngine {
        val html = MarkdownRenderer.page(MarkdownRenderer.body("# Hi", dir), false, standalone = true)
        val engine = WaitForAsyncUtils.asyncFx<WebEngine> { WebEngine() }.get(10, TimeUnit.SECONDS)
        WaitForAsyncUtils.asyncFx { engine.loadContent(html) }.get(10, TimeUnit.SECONDS)
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { engine.loadWorker.state == Worker.State.SUCCEEDED }.get(
                10,
                TimeUnit.SECONDS,
            )
        }
        return engine
    }

    private fun WebEngine.script(js: String): Any? =
        WaitForAsyncUtils.asyncFx<Any?> { executeScript(js) }.get(10, TimeUnit.SECONDS)

    private fun WebEngine.state(): List<Any?> =
        script(
            "var b = document.querySelector('.theme-toggle');" +
                "[document.body.classList.contains('dark'), b.getAttribute('aria-label'), b.title, b.textContent].join('|')",
        ).toString().split('|')

    private fun expected(dark: Boolean) =
        if (dark) {
            listOf("true", "Switch to light theme", "Switch to light theme", "☀")
        } else {
            listOf("false", "Switch to dark theme", "Switch to dark theme", "☾")
        }

    @Test
    fun `the button switches between light and dark and back`() {
        val engine = load()
        assertEquals(true, engine.script("document.querySelector('.theme-toggle') !== null"))
        val initial = engine.script("document.body.classList.contains('dark')") as Boolean
        assertEquals(expected(initial), engine.state())

        engine.script("document.querySelector('.theme-toggle').click()")
        assertNotEquals(initial, engine.script("document.body.classList.contains('dark')") as Boolean)
        assertEquals(expected(!initial), engine.state())

        engine.script("document.querySelector('.theme-toggle').click()")
        assertEquals(expected(initial), engine.state())
    }
}
