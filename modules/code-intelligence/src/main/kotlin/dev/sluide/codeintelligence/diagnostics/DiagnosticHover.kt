package dev.sluide.codeintelligence.diagnostics

import dev.sluide.codeintelligence.ui.HoverProvider
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.eclipse.lsp4j.Diagnostic

/** What the hover shows of one diagnostic: its severity, message (line breaks kept), source and code, and link. */
internal data class DiagnosticHoverContent(
    val severity: Severity,
    val message: String,
    /** Source and code, e.g. "ty · unresolved-reference", `null` without either. */
    val origin: String?,
    /** The `codeDescription` link, shown as text. */
    val link: String?,
)

/** The hover content of [diagnostic]. */
internal fun hoverContent(diagnostic: Diagnostic): DiagnosticHoverContent {
    val code = diagnostic.code?.let { if (it.isLeft) it.left else it.right?.toString() }
    val origin = listOfNotNull(diagnostic.source, code).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }
    return DiagnosticHoverContent(
        severity = Severity.of(diagnostic.severity),
        message = messageOf(diagnostic).trimEnd(),
        origin = origin,
        link = diagnostic.codeDescription?.href?.takeIf { it.isNotBlank() },
    )
}

/**
 * The hover provider of the diagnostics (ADR 0148): one section per diagnostic at the offset ([diagnosticsAt]), first
 * in the hover. [diagnostics] gives those shown now; [stylesheet] colours the icons (the popup has its own styles).
 */
internal fun diagnosticHover(
    diagnostics: () -> List<PlacedDiagnostic>,
    stylesheet: String,
): HoverProvider =
    HoverProvider { offset, done ->
        done(diagnosticsAt(diagnostics(), offset).map { diagnosticSection(hoverContent(it.diagnostic), stylesheet) })
        AutoCloseable {}
    }

private fun diagnosticSection(
    content: DiagnosticHoverContent,
    stylesheet: String,
): Node {
    val message =
        Label(content.message).apply {
            styleClass += "diagnostic-hover-message"
            isWrapText = true
            maxWidth = MESSAGE_WIDTH
        }
    val details =
        listOfNotNull(content.origin, content.link).map { text ->
            Label(text).apply {
                styleClass += "diagnostic-hover-detail"
                isWrapText = true
                maxWidth = MESSAGE_WIDTH
            }
        }
    val icon = severityIcon(content.severity).takeIf { content.severity != Severity.HINT }
    return HBox(*listOfNotNull(icon, VBox(message, *details.toTypedArray())).toTypedArray()).apply {
        styleClass += "diagnostic-hover"
        alignment = Pos.TOP_LEFT
        stylesheets += stylesheet
    }
}

private const val MESSAGE_WIDTH = 580.0
