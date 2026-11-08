package dev.sluide.codeintelligence.diagnostics

import com.google.gson.JsonObject
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.LineIndex
import dev.sluide.fileviewers.StyledRange
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.RequestSlot
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.nio.file.Path
import kotlin.time.Duration

/** The overlay layer of the diagnostics' underlines. */
internal const val DIAGNOSTICS_LAYER = "ci-diagnostics"

internal const val PULL_METHOD = "textDocument/diagnostic"

/**
 * The diagnostics of one bound editor (ADR 0148): pushed ones as they come (dropping those for an older document
 * version), pulled ones when the server answers `textDocument/diagnostic` (ty registers it dynamically) after open,
 * [pullDelay] after the last change, on the server's refresh and after a restart, with the last `resultId`. A pull
 * answered after the document changed is dropped and asked again. All are cleared when the server stops running.
 * Shown as the overlay layer [DIAGNOSTICS_LAYER], which follows edits, and through [shown] (the gutter). FX thread.
 *
 * @param shown told after the diagnostics changed (not after an edit moved them).
 */
internal class DocumentDiagnostics(
    private val binding: DocumentBinding,
    private val pullDelay: Duration,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable,
    private val shown: () -> Unit,
) : AutoCloseable {
    val state = DiagnosticsState(::place)

    private val session = binding.session
    private val slot = RequestSlot()
    private val subscriptions = mutableListOf<AutoCloseable>()
    private var pushSubscription: AutoCloseable? = null
    private var pushFile: Path? = null
    private var pending: AutoCloseable? = null
    private var pullSupported = false
    private var closed = false

    /** The overlay ranges last set, followed through the edits as the editor follows them. */
    private var shownRanges: List<StyledRange> = emptyList()

    init {
        followPushes()
        subscriptions +=
            binding.editor.onTextChange { change ->
                state.edited(change)
                if (shownRanges.isNotEmpty()) shownRanges = diagnosticRanges(state.all)
                pullSoon()
            }
        subscriptions += session.onConnected { connected() }
        val stopped =
            session.state.subscribe { old, now ->
                if (old == ServerState.Running && now != ServerState.Running) serverStopped()
            }
        subscriptions += AutoCloseable { stopped.unsubscribe() }
        subscriptions += session.onFeaturesChanged { featuresChanged() }
        subscriptions += session.onRefresh(RefreshKind.DIAGNOSTICS) { if (pullSupported) pull() }
        val moved = binding.editor.file.subscribe { _, _ -> moved() }
        subscriptions += AutoCloseable { moved.unsubscribe() }
        featuresChanged()
    }

    /** Every diagnostic shown, as it is placed in the editor's text now. */
    val all: List<PlacedDiagnostic> get() = state.all

    /** Pulls now; a pull in flight is cancelled. */
    internal fun pull() {
        if (closed) return
        pending?.close()
        pending = null
        val version = binding.document.version
        val params =
            DocumentDiagnosticParams(binding.documentId).apply {
                previousResultId = state.resultId
                identifier = identifier()
            }
        slot.send(
            session,
            { it.textDocumentService.diagnostic(params) },
            { report ->
                when {
                    closed || report == null -> {
                        Unit
                    }

                    binding.document.version != version -> {
                        pullSoon()
                    }

                    else -> {
                        state.pulled(report)
                        show()
                    }
                }
            },
            { error -> if (error is ResponseErrorException && retriable(error)) pullSoon() },
        )
    }

    private fun pullSoon() {
        if (!pullSupported || closed) return
        pending?.close()
        pending = schedule(pullDelay) { pull() }
    }

    /** The server stopped (crash, restart, window): what it said is no longer current, its result ids are unknown. */
    private fun serverStopped() {
        if (closed) return
        slot.cancel()
        state.cleared()
        show()
    }

    private fun connected() {
        // The features listener follows: a server that answers pulls is asked then.
        pullSupported = false
    }

    private fun featuresChanged() {
        val supported = session.features.supports(PULL_METHOD)
        val was = pullSupported
        pullSupported = supported
        if (supported && !was) {
            pull()
        } else if (!supported && was) {
            state.pullsEnded()
            show()
        }
    }

    /** The editor's file moved (still bound): the document is new on the server, so are its diagnostics. */
    private fun moved() {
        if (closed || binding.file == pushFile) return
        followPushes()
        state.cleared()
        show()
        if (pullSupported) pull()
    }

    private fun followPushes() {
        pushSubscription?.close()
        val file = binding.file
        pushFile = file
        pushSubscription =
            session.onDiagnostics(file) { params ->
                if (!closed &&
                    state.pushed(params.diagnostics.orEmpty().filterNotNull(), params.version, binding.document.version)
                ) {
                    show()
                }
            }
    }

    private fun show() {
        val ranges = diagnosticRanges(state.all)
        if (ranges != shownRanges) {
            binding.editor.overlay(DIAGNOSTICS_LAYER, ranges, true)
            shownRanges = ranges
        }
        shown()
    }

    /** [diagnostics] placed in the document's text, which is the editor's now. */
    private fun place(diagnostics: List<Diagnostic>): List<PlacedDiagnostic> {
        if (diagnostics.isEmpty()) return emptyList()
        val text = binding.document.text
        val lines = LineIndex(text)
        return diagnostics.mapNotNull { diagnostic ->
            val range = diagnostic.range ?: return@mapNotNull null
            val start = lines.offsetOf(range.start ?: return@mapNotNull null)
            val end = range.end?.let(lines::offsetOf) ?: start
            val shownRange = widened(start, end.coerceAtLeast(start), text)
            PlacedDiagnostic(diagnostic, shownRange.first, shownRange.last + 1, start, end.coerceAtLeast(start))
        }
    }

    /** The identifier the server registered its pull diagnostics with, if any. */
    private fun identifier(): String? {
        val options =
            session.features
                .registrations(PULL_METHOD)
                .firstOrNull()
                ?.registerOptions as? JsonObject
        val registered = options?.get("identifier")?.takeIf { it.isJsonPrimitive }?.asString
        return registered ?: session.features.capabilities
            ?.diagnosticProvider
            ?.identifier
    }

    override fun close() {
        closed = true
        pending?.close()
        pending = null
        slot.cancel()
        pushSubscription?.close()
        subscriptions.forEach { it.close() }
        subscriptions.clear()
        binding.editor.overlay(DIAGNOSTICS_LAYER, emptyList(), true)
    }
}

/** Whether the server cancelled the pull and wants it asked again (`ServerCancelled`, `ContentModified`). */
private fun retriable(error: ResponseErrorException): Boolean {
    val code = error.responseError?.code
    return code == ResponseErrorCode.ServerCancelled.value || code == ResponseErrorCode.ContentModified.value
}
