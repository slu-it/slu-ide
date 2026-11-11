package dev.sluide.previews.web

import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import netscape.javascript.JSObject

/**
 * Marks ranges of the text of a page whose text runs are `<span data-o="N">` (N: the offset of the run's first char in
 * the page's flat text; a span covers `[N, N + its text length)`, positions inside it are clamped to it). The page
 * embeds [SCRIPT] in a `<script>` element and styles the marks with the CSS Custom Highlight API,
 * `::highlight(mark-<name>)` (the WebView of JavaFX supports it), not `.mark-<name>`. Marks of different names may
 * overlap. Every call does nothing, or answers null or false, while the page is not loaded.
 *
 * [zoom] is the zoom of the web view (`WebView.zoom`), for [offsetAt]. One [TextMarks] per engine: each takes over
 * the page's [onUserScroll] bridge, the last one wins.
 *
 * FX thread only.
 */
class TextMarks(
    private val engine: WebEngine,
    private val zoom: () -> Double = { 1.0 },
) {
    /** Called when the user scrolls (wheel, keys, touch, scrollbar), not for [reveal]; may be called repeatedly. */
    var onUserScroll: () -> Unit = {}

    /** Held here: the web engine keeps bridge objects weakly. */
    private val bridge = ScrollBridge { onUserScroll() }

    init {
        engine.loadWorker.stateProperty().subscribe { state -> onState(state) } // also delivers the current state
    }

    /** Marks `[start, end)` with the highlight `mark-<name>`, replacing the previous range of that name. */
    fun mark(
        name: String,
        start: Int,
        end: Int,
    ) {
        call("mark(${quote(name)}, $start, $end)")
    }

    fun clear(name: String) {
        call("clear(${quote(name)})")
    }

    /** Scrolls so that [start] is vertically centred, unless it is in view comfortably already. */
    fun reveal(start: Int) {
        call("reveal($start)")
    }

    /** Whether any part of `[start, end)` is inside the viewport. */
    fun isVisible(
        start: Int,
        end: Int,
    ): Boolean = call("isVisible($start, $end)") == true

    /** The offset of the first text visible at the top of the viewport. */
    fun topOffset(): Int? = (call("topOffset()") as? Number)?.toInt()

    /** The offset of the char under [x], [y] (web view coordinates, scaled by the web view's zoom). */
    fun offsetAt(
        x: Double,
        y: Double,
    ): Int? = (call("offsetAt(${x / zoom()}, ${y / zoom()})") as? Number)?.toInt()

    private fun onState(state: Worker.State?) {
        if (state != Worker.State.SUCCEEDED) return
        val window = engine.executeScript("window") as JSObject
        window.setMember(BRIDGE_MEMBER, bridge)
    }

    private fun call(call: String): Any? {
        if (engine.loadWorker.state != Worker.State.SUCCEEDED) return null
        return engine.executeScript("(window.sluide && sluide.marks) ? sluide.marks.$call : null")
    }

    private fun quote(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        private const val BRIDGE_MEMBER = "sluideMarksBridge"

        /** The script, for a `<script>` element of the page. */
        val SCRIPT: String by lazy {
            TextMarks::class.java
                .getResourceAsStream("text-marks.js")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}

/** What the page's script calls (`sluideMarksBridge`); public members only, it is reached by reflection. */
internal class ScrollBridge(
    private val scrolled: () -> Unit,
) {
    fun userScrolled() = scrolled()
}
