package dev.sluide.codeintelligence.diagnostics

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.edits.BindingEditTarget
import dev.sluide.codeintelligence.edits.WorkspaceEdits
import dev.sluide.codeintelligence.quickfix.QuickFixes
import dev.sluide.codeintelligence.ui.fxDelay
import dev.sluide.fileviewers.ExtensibleCodeEditor
import java.util.WeakHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The stylesheet of the underlines, the gutter icons and the hover sections. */
internal val DIAGNOSTICS_CSS: String =
    checkNotNull(DiagnosticsFeature::class.java.getResource("diagnostics.css")).toExternalForm()

/**
 * Diagnostics and quick fixes of a bound editor (ADR 0148): the diagnostics ([DocumentDiagnostics]) underlined in the
 * text, as icons in the gutter ([DiagnosticsGutter], one column per editor for its life) and first in the hover; Alt+Enter
 * and "Show Quick Fixes" ([QuickFixes]) apply the server's fixes through [WorkspaceEdits]. FX thread.
 *
 * @param pullDelay how long after the last change the diagnostics are pulled again.
 * @param workspaceEdits how a binding's workspace edits are applied.
 */
internal class DiagnosticsFeature(
    libraryReadOnly: () -> Boolean,
    private val pullDelay: Duration = 300.milliseconds,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
    private val workspaceEdits: (
        DocumentBinding,
    ) -> WorkspaceEdits = { WorkspaceEdits(BindingEditTarget(it), libraryReadOnly = libraryReadOnly) },
) : BindingFeature {
    private val gutters = WeakHashMap<ExtensibleCodeEditor, DiagnosticsGutter>()

    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        val gutter = gutters.getOrPut(editor) { DiagnosticsGutter(editor) }
        val stylesheet = editor.addStylesheet(DIAGNOSTICS_CSS)
        val diagnostics = DocumentDiagnostics(binding, pullDelay, schedule) { gutter.changed() }
        gutter.show { diagnostics.all to binding.document.text }
        val hover = binding.hovers.add(diagnosticHover({ diagnostics.all }, DIAGNOSTICS_CSS))
        val quickFixes = QuickFixes(binding, { diagnostics.all }, workspaceEdits(binding))
        return AutoCloseable {
            quickFixes.close()
            hover.close()
            diagnostics.close()
            gutter.show(null)
            stylesheet.close()
        }
    }
}
