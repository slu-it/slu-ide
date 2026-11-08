package dev.sluide.codeintelligence.diagnostics

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextChange
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Range

/** How bad a diagnostic is; [rank] orders them, the worst highest. */
internal enum class Severity(
    val rank: Int,
    /** The style class of its underline and icons, `null` for none (hints). */
    val styleClass: String?,
) {
    HINT(0, null),
    INFORMATION(1, "diagnostic-information"),
    WARNING(2, "diagnostic-warning"),
    ERROR(3, "diagnostic-error"),
    ;

    companion object {
        /** The severity of [severity]; a diagnostic without one counts as an error, as VS Code shows it. */
        fun of(severity: DiagnosticSeverity?): Severity =
            when (severity) {
                DiagnosticSeverity.Hint -> HINT
                DiagnosticSeverity.Information -> INFORMATION
                DiagnosticSeverity.Warning -> WARNING
                DiagnosticSeverity.Error, null -> ERROR
            }
    }
}

/**
 * A diagnostic shown in the editor's text at [start, end) (widened, [widened]); its own range is [ownStart, ownEnd).
 * Both follow edits ([followEdit]).
 */
internal data class PlacedDiagnostic(
    val diagnostic: Diagnostic,
    val start: Int,
    val end: Int,
    val ownStart: Int = start,
    val ownEnd: Int = end,
) {
    val severity: Severity get() = Severity.of(diagnostic.severity)
}

/** The style classes of [diagnostic] in the text: its severity's underline, faded if unnecessary, struck if deprecated. */
internal fun diagnosticClasses(diagnostic: Diagnostic): List<String> {
    val tags = diagnostic.tags.orEmpty()
    return listOfNotNull(
        Severity.of(diagnostic.severity).styleClass,
        "diagnostic-unnecessary".takeIf { DiagnosticTag.Unnecessary in tags },
        "diagnostic-deprecated".takeIf { DiagnosticTag.Deprecated in tags },
    )
}

/** The message of [diagnostic] as plain text (Markdown messages as written). */
internal fun messageOf(diagnostic: Diagnostic): String =
    diagnostic.message?.let { if (it.isLeft) it.left else it.right?.value }.orEmpty()

/** The overlay ranges of [diagnostics], one per class, without empty ones. */
internal fun diagnosticRanges(diagnostics: List<PlacedDiagnostic>): List<StyledRange> =
    diagnostics.filter { it.end > it.start }.flatMap { placed ->
        diagnosticClasses(placed.diagnostic).map { StyledRange(placed.start, placed.end, it) }
    }

/**
 * The range a diagnostic at [start, end) of [text] is shown at: as it is unless empty; an empty one covers the
 * character after it, or before it at the end of a line; empty on an empty line.
 */
internal fun widened(
    start: Int,
    end: Int,
    text: CharSequence,
): IntRange {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    if (to > from) return from until to
    return when {
        from < text.length && text[from] != '\n' -> from until from + 1
        from > 0 && text[from - 1] != '\n' -> from - 1 until from
        else -> from until from
    }
}

/**
 * [diagnostics] after [change], as the editor's following overlay moves them: unchanged entirely before the changed
 * range, moved entirely after it, dropped when they overlap or touch it (the server's next answer brings them back).
 */
internal fun followEdit(
    diagnostics: List<PlacedDiagnostic>,
    change: TextChange,
): List<PlacedDiagnostic> {
    val start = change.start
    val end = start + change.removed.length
    val delta = change.inserted.length - change.removed.length
    return diagnostics.mapNotNull { placed ->
        when {
            placed.end < start -> {
                placed
            }

            placed.start > end -> {
                placed.copy(
                    start = placed.start + delta,
                    end = placed.end + delta,
                    ownStart = placed.ownStart + delta,
                    ownEnd = placed.ownEnd + delta,
                )
            }

            else -> {
                null
            }
        }
    }
}

/**
 * The diagnostics at [offset] for the hover: those whose range contains it, and empty ones at it. In the order given.
 */
internal fun diagnosticsAt(
    diagnostics: List<PlacedDiagnostic>,
    offset: Int,
): List<PlacedDiagnostic> =
    diagnostics.filter {
        offset in it.start until it.end ||
            (it.start == it.end && it.start == offset)
    }

/**
 * The diagnostics a quick fix request for [start, end) sends: with a selection those overlapping it, at a caret
 * ([start] equals [end]) those it is in or at either end of.
 */
internal fun diagnosticsFor(
    diagnostics: List<PlacedDiagnostic>,
    start: Int,
    end: Int,
): List<PlacedDiagnostic> =
    diagnostics
        .filter { placed ->
            if (start == end) {
                start in placed.start..placed.end
            } else {
                val overlapping = placed.start < end && start < placed.end
                overlapping || (placed.start == placed.end && placed.start in start until end)
            }
        }

/** The gutter marker of one line: the worst severity on it and the messages of its diagnostics, in order. */
internal data class LineMarker(
    val severity: Severity,
    val messages: List<String>,
)

/**
 * The gutter markers by line (0-based) of [diagnostics]: each on the line it starts on ([lineOf] an offset), hints
 * left out, so a line with hints only has none.
 */
internal fun lineMarkers(
    diagnostics: List<PlacedDiagnostic>,
    lineOf: (Int) -> Int,
): Map<Int, LineMarker> {
    val markers = sortedMapOf<Int, LineMarker>()
    for (placed in diagnostics) {
        if (placed.severity == Severity.HINT) continue
        val line = lineOf(placed.start)
        val message = messageOf(placed.diagnostic)
        val before = markers[line]
        markers[line] =
            if (before == null) {
                LineMarker(placed.severity, listOf(message))
            } else {
                LineMarker(maxOf(before.severity, placed.severity, compareBy { it.rank }), before.messages + message)
            }
    }
    return markers
}

/** [diagnostic] with [range] instead of its own: where it is in the text now, for a request about it. */
internal fun withRange(
    diagnostic: Diagnostic,
    range: Range,
): Diagnostic =
    Diagnostic().apply {
        this.range = range
        severity = diagnostic.severity
        code = diagnostic.code
        codeDescription = diagnostic.codeDescription
        source = diagnostic.source
        message = diagnostic.message
        tags = diagnostic.tags
        relatedInformation = diagnostic.relatedInformation
        data = diagnostic.data
    }
