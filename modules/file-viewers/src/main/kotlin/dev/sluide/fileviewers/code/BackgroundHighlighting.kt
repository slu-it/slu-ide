package dev.sluide.fileviewers.code

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.SyntaxHighlighter
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans
import org.reactfx.Subscription
import java.time.Duration

/**
 * Highlights the text of [area] again once typing pauses for [pause], on a background thread shared by all editors;
 * the result is handed to the FX thread through [dispatch] and dropped if the text changed meanwhile (or after
 * [dispose]). Only the range whose styles changed is restyled. [restyle] adds to the token styles of a text, e.g.
 * the find bar's matches. Does nothing without a [highlighter].
 */
internal class BackgroundHighlighting(
    private val area: CodeArea,
    private val highlighter: SyntaxHighlighter?,
    pause: Duration,
    private val dispatch: (Runnable) -> Unit,
    private val restyle: (text: String, spans: StyleSpans<Collection<String>>) -> StyleSpans<Collection<String>> =
        { _, spans -> spans },
) {
    /** Counts the changes of the text, so that highlighting done for an older text is dropped. */
    private var revision = 0L

    /** The revision highlighting was asked for last; the worker skips older requests still waiting for it. */
    @Volatile private var requested = 0L
    private var disposed = false

    private val subscription: Subscription =
        Subscription.multi(
            area.plainTextChanges().subscribe { revision++ },
            area.multiPlainChanges().successionEnds(pause).subscribe { highlight() },
        )

    fun dispose() {
        disposed = true
        subscription.unsubscribe()
    }

    private fun highlight() {
        val highlighter = highlighter ?: return
        val text = area.text
        if (text.isEmpty()) return
        val highlighted = revision
        requested = highlighted
        highlightExecutor.execute {
            if (requested != highlighted) return@execute
            val spans = codeStyleSpans(text, highlighter)
            dispatch(
                Runnable {
                    if (disposed || revision != highlighted) return@Runnable
                    val styles = restyle(text, spans)
                    val range = changedSpanRange(area.getStyleSpans(0, area.length), styles) ?: return@Runnable
                    area.setStyleSpans(range.first, styles.subView(range.first, range.last + 1))
                },
            )
        }
    }
}

/** Highlights edited code of all views, one text at a time. */
private val highlightExecutor = NamedThreads.single("sluide-highlight")
