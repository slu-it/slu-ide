package dev.sluide.codeintelligence.diagnostics

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextChange
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticCodeDescription
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/**
 * What the diagnostics look like and where: classes per severity and tag, empty ranges widened, ranges following
 * edits as the overlay does, the gutter's worst severity per line, what the hover and a quick fix request take.
 */
class PlacedDiagnosticsTest {
    private fun diagnostic(
        message: String,
        severity: DiagnosticSeverity? = DiagnosticSeverity.Error,
        vararg tags: DiagnosticTag,
    ) = Diagnostic(Range(Position(0, 0), Position(0, 0)), message).apply {
        this.severity = severity
        this.tags = tags.toList().ifEmpty { null }
    }

    private fun placed(
        start: Int,
        end: Int,
        message: String = "m",
        severity: DiagnosticSeverity? = DiagnosticSeverity.Error,
    ) = PlacedDiagnostic(diagnostic(message, severity), start, end)

    @Test
    fun `each severity has its underline, hints none, and tags fade or strike the text`() {
        assertEquals(listOf("diagnostic-error"), diagnosticClasses(diagnostic("e")))
        assertEquals(listOf("diagnostic-error"), diagnosticClasses(diagnostic("no severity", null)))
        assertEquals(listOf("diagnostic-warning"), diagnosticClasses(diagnostic("w", DiagnosticSeverity.Warning)))
        assertEquals(
            listOf("diagnostic-information"),
            diagnosticClasses(diagnostic("i", DiagnosticSeverity.Information)),
        )
        assertEquals(
            listOf("diagnostic-unnecessary"),
            diagnosticClasses(diagnostic("unused", DiagnosticSeverity.Hint, DiagnosticTag.Unnecessary)),
        )
        assertEquals(
            listOf("diagnostic-warning", "diagnostic-deprecated"),
            diagnosticClasses(diagnostic("old", DiagnosticSeverity.Warning, DiagnosticTag.Deprecated)),
        )
        assertEquals(emptyList(), diagnosticRanges(listOf(placed(0, 2, severity = DiagnosticSeverity.Hint))))
        assertEquals(
            listOf(StyledRange(0, 2, "diagnostic-error")),
            diagnosticRanges(listOf(placed(0, 2), placed(3, 3))),
        )
    }

    @ParameterizedTest(name = "[{0}, {1}) -> [{2}, {3})")
    @CsvSource(
        "1, 3, 1, 3",
        // Empty: the character after, or before it at a line's end, nothing on an empty line.
        "1, 1, 1, 2",
        "2, 2, 1, 2",
        "3, 3, 3, 3",
        "6, 6, 5, 6",
    )
    fun `an empty range is widened to the character after it, or before it at the end of a line`(
        start: Int,
        end: Int,
        expectedStart: Int,
        expectedEnd: Int,
    ) {
        // "ab", an empty line, "cd": 2 and 3 are the line breaks, 6 the end of the text.
        val shown = widened(start, end, "ab\n\ncd")
        assertEquals(expectedStart until expectedEnd, shown)
    }

    @Test
    fun `diagnostics before an edit stay, those after it move, those touching it are dropped`() {
        val before = placed(0, 2, "before")
        val touching = placed(3, 5, "touching")
        // Widened from an empty range at 8: its own range follows too.
        val after = placed(8, 9, "after").copy(ownEnd = 8)
        val followed = followEdit(listOf(before, touching, after), TextChange(5, "x", "yyy"))
        assertEquals(listOf(before, after.copy(start = 10, end = 11, ownStart = 10, ownEnd = 10)), followed)
    }

    @Test
    fun `the gutter marks each line with its worst severity and all messages, hints left out`() {
        val lineOf = { offset: Int -> offset / 10 }
        val markers =
            lineMarkers(
                listOf(
                    placed(1, 2, "warn", DiagnosticSeverity.Warning),
                    placed(3, 4, "err"),
                    placed(12, 13, "hint", DiagnosticSeverity.Hint),
                    placed(25, 40, "info", DiagnosticSeverity.Information),
                ),
                lineOf,
            )
        assertEquals(
            mapOf(
                0 to LineMarker(Severity.ERROR, listOf("warn", "err")),
                2 to LineMarker(Severity.INFORMATION, listOf("info")),
            ),
            markers,
        )
    }

    @Test
    fun `the hover takes the diagnostics containing the offset, a quick fix those at the caret or in the selection`() {
        val name = placed(4, 8, "name")
        val empty = placed(10, 10, "empty")
        val all = listOf(name, empty)
        assertEquals(listOf(name), diagnosticsAt(all, 4))
        assertEquals(emptyList(), diagnosticsAt(all, 8))
        assertEquals(listOf(empty), diagnosticsAt(all, 10))

        assertEquals(listOf(name), diagnosticsFor(all, 8, 8), "the caret right after the name")
        assertEquals(emptyList(), diagnosticsFor(all, 0, 4), "a selection that ends where it starts")
        assertEquals(listOf(name, empty), diagnosticsFor(all, 7, 11))
    }

    @Test
    fun `the hover shows the message with its line breaks, source and code, and the link`() {
        val full =
            diagnostic("Name `x` used\nwhen not defined\n").apply {
                source = "ty"
                code = Either.forLeft("unresolved-reference")
                codeDescription = DiagnosticCodeDescription("https://ty.dev/rules#unresolved-reference")
            }
        assertEquals(
            DiagnosticHoverContent(
                Severity.ERROR,
                "Name `x` used\nwhen not defined",
                "ty · unresolved-reference",
                "https://ty.dev/rules#unresolved-reference",
            ),
            hoverContent(full),
        )
        val bare = diagnostic("w", DiagnosticSeverity.Warning).apply { code = Either.forRight(42) }
        assertEquals(DiagnosticHoverContent(Severity.WARNING, "w", "42", null), hoverContent(bare))
    }

    @Test
    fun `a diagnostic sent back with another range keeps everything else`() {
        val original =
            diagnostic("unused", DiagnosticSeverity.Hint, DiagnosticTag.Unnecessary).apply {
                source = "ty"
                code = Either.forLeft("unused")
                data = "token"
            }
        val moved = Range(Position(2, 0), Position(2, 3))
        val sent = withRange(original, moved)
        assertEquals(moved, sent.range)
        assertEquals(original.apply { range = moved }, sent)
    }
}
