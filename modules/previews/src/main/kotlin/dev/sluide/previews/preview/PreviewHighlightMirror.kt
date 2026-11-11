package dev.sluide.previews.preview

import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.StyledRange
import dev.sluide.previews.web.TextMarks
import javafx.beans.value.ObservableValue
import javafx.scene.web.WebView
import javafx.util.Subscription

/**
 * The style classes of overlay layers that the preview shows: `preview.css` styles them as `::highlight(mark-<class>)`.
 * Keep in sync with it; Read Aloud's highlight (ADR 0166).
 */
internal val MIRRORED_STYLES = setOf("speech-sentence", "speech-word")

/** What [PreviewHighlightMirror] does in the page: [TextMarks] by source offset. */
internal interface HighlightPage {
    fun mark(
        name: String,
        start: Int,
        end: Int,
    )

    fun clear(name: String)

    fun reveal(start: Int)

    fun isVisible(
        start: Int,
        end: Int,
    ): Boolean
}

/** The range `[start, end)` of a mark in the page. */
internal data class MarkRange(
    val start: Int,
    val end: Int,
)

/**
 * Mirrors the overlay layers of the source ([CodeEditor.addOverlayListener]) into the rendered page, whose texts carry
 * their source offsets (ADR 0166). Each style class of [styles] is a mark of that name, covering all ranges of that
 * class in all layers (Read Aloud has one sentence and one word); other classes are not shown, as the page does not
 * style them. While [previewAlone] (the source hidden), the last range of a layer that changed is revealed if it is
 * out of view; side by side the scroll sync moves the page. The page shows the saved text, so nothing is marked while
 * the source does not [match it][matchesPage] (unsaved changes). [loaded]: a new page, marked again. After [dispose]
 * it does nothing. FX thread.
 */
internal class PreviewHighlightMirror(
    private val page: HighlightPage,
    private val previewAlone: () -> Boolean,
    private val matchesPage: () -> Boolean,
    private val styles: Set<String> = MIRRORED_STYLES,
) {
    private val layers = LinkedHashMap<String, List<StyledRange>>()

    /** The marks in the page, in the order they were set: a later one paints over an earlier one. */
    private var shown: Map<String, MarkRange> = emptyMap()

    /** What tells this mirror the source's layers ([attach]). */
    private var subscription = Subscription.EMPTY
    private var disposed = false

    /** The source's [layer] has [ranges] now (none: cleared). */
    fun overlaid(
        layer: String,
        ranges: List<StyledRange>,
    ) {
        if (disposed) return
        val kept = ranges.filter { it.styleClass in styles }
        if (kept.isEmpty() && layer !in layers) return
        if (kept.isEmpty()) layers.remove(layer) else layers[layer] = kept
        apply()
        val last = kept.lastOrNull() ?: return
        if (previewAlone() && last.styleClass in shown && !page.isVisible(last.start, last.end)) page.reveal(last.start)
    }

    /** The page was loaded anew, without marks: sets them again. */
    fun loaded() {
        if (disposed) return
        shown = emptyMap()
        apply()
    }

    /** Stops following the source and marking the page, whose engine goes with the view. */
    fun dispose() {
        disposed = true
        subscription.unsubscribe()
    }

    private fun apply() {
        val wanted = if (matchesPage()) marks(layers.values) else emptyMap()
        val names = wanted.keys.toList()
        // A new mark set after one that should paint over it would paint over that one: set them all again in order.
        val reordered =
            names.withIndex().any { (index, name) -> name !in shown && names.drop(index + 1).any { it in shown } }
        val kept = if (reordered) emptyMap() else shown
        for (name in shown.keys) if (name !in wanted || name !in kept) page.clear(name)
        for ((name, range) in wanted) if (kept[name] != range) page.mark(name, range.start, range.end)
        shown = wanted
    }

    companion object {
        /**
         * A mirror of the overlay layers of [source] into the page of [webView], marked with [TextMarks], if [preview]
         * [mirrors highlights][Preview.mirrorsHighlights]; it reveals in the [mode] Preview. Its [loaded] is to be
         * called when the view's own page has loaded.
         */
        fun attach(
            preview: Preview,
            source: CodeEditor,
            webView: WebView,
            mode: ObservableValue<PreviewMode>,
        ): PreviewHighlightMirror? {
            if (!preview.mirrorsHighlights) return null
            val marks = TextMarks(webView.engine) { webView.zoom }
            val page =
                object : HighlightPage {
                    override fun mark(
                        name: String,
                        start: Int,
                        end: Int,
                    ) = marks.mark(name, start, end)

                    override fun clear(name: String) = marks.clear(name)

                    override fun reveal(start: Int) = marks.reveal(start)

                    override fun isVisible(
                        start: Int,
                        end: Int,
                    ) = marks.isVisible(start, end)
                }
            val mirror =
                PreviewHighlightMirror(page, { mode.value == PreviewMode.PREVIEW }, { !source.dirty.get() })
            mirror.subscription = source.addOverlayListener(mirror::overlaid)
            return mirror
        }
    }
}

/**
 * The marks of [layers] (in the order set), by style class in the order the classes first appear: each from the
 * first start to the last end of the ranges of its class.
 */
internal fun marks(layers: Collection<List<StyledRange>>): Map<String, MarkRange> {
    val marks = LinkedHashMap<String, MarkRange>()
    for (range in layers.flatten()) {
        val mark = marks[range.styleClass]
        marks[range.styleClass] =
            if (mark == null) {
                MarkRange(range.start, range.end)
            } else {
                MarkRange(minOf(mark.start, range.start), maxOf(mark.end, range.end))
            }
    }
    return marks
}
