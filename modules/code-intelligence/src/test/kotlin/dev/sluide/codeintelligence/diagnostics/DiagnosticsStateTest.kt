package dev.sluide.codeintelligence.diagnostics

import dev.sluide.fileviewers.TextChange
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport
import org.eclipse.lsp4j.RelatedUnchangedDocumentDiagnosticReport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two sources of a document's diagnostics: pushed sets replace the pushed ones unless for an older version, pulled
 * full reports the pulled ones, unchanged reports place the last full one again; result ids reset with a restart.
 */
class DiagnosticsStateTest {
    /** Places a diagnostic on line 0 at its character, `shift` further (as if the text had moved on). */
    private var shift = 0
    private val state =
        DiagnosticsState { diagnostics ->
            diagnostics.map { PlacedDiagnostic(it, it.range.start.character + shift, it.range.end.character + shift) }
        }

    private fun diagnostic(
        message: String,
        start: Int,
        end: Int,
    ) = Diagnostic(Range(Position(0, start), Position(0, end)), message)

    private fun full(
        resultId: String?,
        vararg diagnostics: Diagnostic,
    ): DocumentDiagnosticReport {
        val report = RelatedFullDocumentDiagnosticReport(diagnostics.toList())
        report.resultId = resultId
        return DocumentDiagnosticReport(report)
    }

    private fun unchanged(resultId: String) =
        DocumentDiagnosticReport(RelatedUnchangedDocumentDiagnosticReport(resultId))

    private val messages get() = state.all.map { messageOf(it.diagnostic) }

    @Test
    fun `pushed diagnostics for an older version are dropped, for this one or none they replace the pushed ones`() {
        assertTrue(state.pushed(listOf(diagnostic("a", 0, 1)), version = null, documentVersion = 3))
        assertFalse(state.pushed(listOf(diagnostic("old", 0, 1)), version = 2, documentVersion = 3))
        assertEquals(listOf("a"), messages)
        assertTrue(state.pushed(listOf(diagnostic("b", 0, 1)), version = 3, documentVersion = 3))
        assertEquals(listOf("b"), messages)
    }

    @Test
    fun `a full report replaces the pulled ones next to the pushed, an unchanged one places the last full one again`() {
        state.pushed(listOf(diagnostic("pushed", 0, 1)), null, 1)
        state.pulled(full("r1", diagnostic("pulled", 4, 6)))
        assertEquals(listOf("pulled", "pushed"), messages)
        assertEquals("r1", state.resultId)

        state.edited(TextChange(0, "", "xx"))
        assertEquals(listOf(6 until 8), state.all.map { it.start until it.end }.take(1), "the pulled one moved")
        assertEquals(listOf("pulled"), messages, "the pushed one touched the edit")

        shift = 2
        state.pulled(unchanged("r1"))
        assertEquals(listOf(6 until 8), state.all.map { it.start until it.end })
        assertEquals("r1", state.resultId)

        state.pulled(full("r2"))
        assertEquals(emptyList(), messages)
        assertEquals("r2", state.resultId)
    }

    @Test
    fun `the end of pulls takes the pulled diagnostics and the result id, a move or stop all of them`() {
        state.pushed(listOf(diagnostic("pushed", 0, 1)), null, 1)
        state.pulled(full("r1", diagnostic("pulled", 2, 3)))
        state.pullsEnded()
        assertEquals(listOf("pushed"), messages)
        assertNull(state.resultId)

        state.pulled(full("r2", diagnostic("pulled", 2, 3)))
        state.cleared()
        assertEquals(emptyList(), messages)
        assertNull(state.resultId)
    }
}
