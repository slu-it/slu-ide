package dev.sluide.codeintelligence.diagnostics

import dev.sluide.fileviewers.TextChange
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentDiagnosticReport

/**
 * The diagnostics of one document from both sources (ADR 0148): those the server pushes (`publishDiagnostics`) and
 * those the client pulls (`textDocument/diagnostic`), each placed in the editor's text when they arrive and following
 * its edits from then on. A pushed set replaces the pushed ones, a pulled full report the pulled ones; an unchanged
 * report places the last full report's diagnostics again (they are the server's answer for the current text). Plain
 * class, FX thread.
 *
 * @param place the diagnostics placed in the editor's current text.
 */
internal class DiagnosticsState(
    private val place: (List<Diagnostic>) -> List<PlacedDiagnostic>,
) {
    private var pushed: List<PlacedDiagnostic> = emptyList()
    private var pulled: List<PlacedDiagnostic> = emptyList()
    private var lastFullReport: List<Diagnostic> = emptyList()

    /** The `resultId` of the last pulled report, sent as `previousResultId` with the next pull. */
    var resultId: String? = null
        private set

    /** Every diagnostic shown: the pulled ones, then the pushed ones. */
    val all: List<PlacedDiagnostic> get() = pulled + pushed

    /**
     * The server pushed [diagnostics] for the document [version] (`null`: not said) while it is at [documentVersion];
     * `false` (dropped) for an older version, whose ranges are for a text the editor no longer shows.
     */
    fun pushed(
        diagnostics: List<Diagnostic>,
        version: Int?,
        documentVersion: Int,
    ): Boolean {
        if (version != null && version < documentVersion) return false
        pushed = place(diagnostics)
        return true
    }

    /** A pull answered with [report] for the document as it is now (the caller dropped answers for older texts). */
    fun pulled(report: DocumentDiagnosticReport) {
        if (report.isRelatedFullDocumentDiagnosticReport) {
            val full = report.relatedFullDocumentDiagnosticReport
            lastFullReport = full.items.orEmpty().filterNotNull()
            resultId = full.resultId
        } else {
            resultId = report.relatedUnchangedDocumentDiagnosticReport.resultId ?: resultId
        }
        pulled = place(lastFullReport)
    }

    /** The server no longer answers pulls: the pulled diagnostics go. */
    fun pullsEnded() {
        pulled = emptyList()
        lastFullReport = emptyList()
        resultId = null
    }

    /**
     * The document moved (a new document on the server) or the server stopped (a new one knows none of the result
     * ids): every diagnostic goes, they come anew.
     */
    fun cleared() {
        pullsEnded()
        pushed = emptyList()
    }

    /** The editor's text changed by [change]: the diagnostics follow it as the overlay does. */
    fun edited(change: TextChange) {
        pushed = followEdit(pushed, change)
        pulled = followEdit(pulled, change)
    }
}
