package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.ui.fxDelay
import dev.sluide.fileviewers.StyledRange
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The overlay layer of the occurrences of the name at the caret. */
internal const val OCCURRENCE_LAYER = "ci-occurrence"

private val OCCURRENCE_CSS: String =
    checkNotNull(OccurrencesFeature::class.java.getResource("occurrences.css")).toExternalForm()

/** What an [OccurrenceModel] needs of the editor and the server. */
internal interface OccurrenceHost {
    /** The word at the caret [offset] (also one ending there), `start until end`; `null` for none. */
    fun wordAt(offset: Int): IntRange?

    /** Whether the server can be asked now. */
    fun canAsk(): Boolean

    /** Asks for the occurrences of the name at [offset]; [done] gets them once; closing the handle drops the answer. */
    fun ask(
        offset: Int,
        done: (List<StyledRange>) -> Unit,
    ): AutoCloseable

    /** Shows [ranges] as the occurrences, replacing those shown; none clears them. */
    fun show(ranges: List<StyledRange>)
}

/**
 * When the occurrences of the name at the caret are asked and cleared (ADR 0146): the caret resting on a word for
 * [delay] asks; leaving the word clears them and drops a question on its way. An edit keeps what shows (the layer
 * follows edits) and asks again for the word at the caret after the delay. FX thread.
 *
 * @param schedule runs the action after the delay unless the handle is closed first.
 */
internal class OccurrenceModel(
    private val host: OccurrenceHost,
    private val delay: Duration,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable,
) {
    /** The word the occurrences shown or asked for are of. */
    private var word: IntRange? = null
    private var timer: AutoCloseable? = null
    private var question: AutoCloseable? = null
    private var shown = false

    /** The caret moved to [offset]. */
    fun caretMoved(offset: Int) {
        val now = host.wordAt(offset)
        if (now != null && now == word) return
        cancel()
        if (shown) {
            shown = false
            host.show(emptyList())
        }
        askLater(now, offset)
    }

    /** The text changed with the caret now at [caret]: asks again, keeping what shows meanwhile. */
    fun textChanged(caret: Int) {
        cancel()
        askLater(host.wordAt(caret), caret)
    }

    /** Clears what shows and drops what is on its way. */
    fun close() {
        cancel()
        word = null
        if (shown) {
            shown = false
            host.show(emptyList())
        }
    }

    private fun askLater(
        now: IntRange?,
        caret: Int,
    ) {
        word = now
        if (now == null) {
            if (shown) {
                shown = false
                host.show(emptyList())
            }
            return
        }
        val at = if (caret in now) caret else now.first
        timer =
            schedule(delay) {
                timer = null
                if (host.canAsk()) {
                    question =
                        host.ask(at) { ranges ->
                            question = null
                            shown = ranges.isNotEmpty()
                            host.show(ranges)
                        }
                } else {
                    // Asked again on the next caret move, also within this word (the server may run by then).
                    word = null
                }
            }
    }

    private fun cancel() {
        timer?.close()
        timer = null
        question?.close()
        question = null
    }
}

/**
 * Highlights the occurrences of the name at the caret in a bound editor (ADR 0146): `textDocument/documentHighlight`
 * after the caret rests for [delay] ([OccurrenceModel]), shown as the layer [OCCURRENCE_LAYER], which follows edits,
 * with a class per kind (read, write, text).
 */
internal class OccurrencesFeature(
    private val delay: Duration = 300.milliseconds,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        val requests = RequestSlot()
        val host =
            object : OccurrenceHost {
                override fun wordAt(offset: Int) = editor.wordAt(offset, orBefore = true)

                override fun canAsk() = navigationBlocker(binding, DOCUMENT_HIGHLIGHT) == null

                override fun ask(
                    offset: Int,
                    done: (List<StyledRange>) -> Unit,
                ): AutoCloseable {
                    val params = DocumentHighlightParams(binding.documentId, binding.positionOf(offset))
                    requests.send(
                        binding.session,
                        { server -> server.textDocumentService.documentHighlight(params) },
                        { highlights -> if (!binding.isClosed) done(highlights.orEmpty().mapNotNull(::styled)) },
                        // Not running any more, or the server refused: nothing to highlight.
                        { if (!binding.isClosed) done(emptyList()) },
                    )
                    return AutoCloseable { requests.cancel() }
                }

                override fun show(ranges: List<StyledRange>) =
                    editor.overlay(OCCURRENCE_LAYER, ranges, followEdits = true)

                private fun styled(highlight: DocumentHighlight?): StyledRange? {
                    val range = highlight?.range ?: return null
                    val offsets = binding.offsetsOf(range)
                    if (offsets.isEmpty()) return null
                    return StyledRange(offsets.first, offsets.last + 1, occurrenceClass(highlight.kind))
                }
            }
        val model = OccurrenceModel(host, delay, schedule)
        val handles =
            listOf(
                editor.addStylesheet(OCCURRENCE_CSS),
                editor.caretOffsetProperty
                    .subscribe { _, offset -> model.caretMoved(offset.toInt()) }
                    .let { subscription -> AutoCloseable { subscription.unsubscribe() } },
                editor.onTextChange { model.textChanged(editor.caretOffset) },
                AutoCloseable { model.close() },
            )
        return AutoCloseable { handles.asReversed().forEach { it.close() } }
    }
}

/** The class of an occurrence of [kind]: read, write or text (also for none). */
internal fun occurrenceClass(kind: DocumentHighlightKind?): String =
    when (kind) {
        DocumentHighlightKind.Read -> "ci-occurrence-read"
        DocumentHighlightKind.Write -> "ci-occurrence-write"
        DocumentHighlightKind.Text, null -> "ci-occurrence-text"
    }
