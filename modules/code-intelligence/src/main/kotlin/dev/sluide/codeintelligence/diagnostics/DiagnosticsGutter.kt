package dev.sluide.codeintelligence.diagnostics

import dev.sluide.codeintelligence.binding.LineIndex
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.Node
import javafx.scene.control.Tooltip
import javafx.scene.layout.Region
import javafx.util.Duration
import java.lang.ref.WeakReference

/** The width of the gutter column of the diagnostics' icons. */
private const val COLUMN_WIDTH = 12.0

/**
 * The gutter column of one editor's diagnostics (ADR 0148): per line the icon of its worst diagnostic (hints have
 * none), with the messages as tooltip. An editor's gutter columns cannot be removed, so there is one per editor for its
 * life ([DiagnosticsFeature] keeps it), showing the diagnostics of its current binding ([source]). The markers are
 * worked out when a row asks after a change; [changed] creates the shown rows again only if a shown line's marker
 * changed. FX thread.
 */
internal class DiagnosticsGutter(
    editor: ExtensibleCodeEditor,
) {
    // Weak: the feature keeps the gutters in a WeakHashMap by editor, whose values must not hold their keys.
    private val editorRef = WeakReference(editor)

    /** The diagnostics shown and the text they are placed in, `null` while the editor is not bound. */
    private var source: (() -> Pair<List<PlacedDiagnostic>, CharSequence>)? = null

    /** The markers by line, `null` after a change until a row asks. */
    private var markers: Map<Int, LineMarker>? = null
    private var lastMarkers: Map<Int, LineMarker> = emptyMap()

    init {
        editor.addGutterColumn(COLUMN_WIDTH, ::column)
        // As long as the editor lives, like the column.
        editor.onTextChange { markers = null }
    }

    /** Shows the diagnostics [source] gives from now on, `null` for none. */
    fun show(source: (() -> Pair<List<PlacedDiagnostic>, CharSequence>)?) {
        this.source = source
        changed()
    }

    /** The diagnostics changed: the shown rows are created again if the marker of one of them changed. */
    fun changed() {
        val before = lastMarkers
        markers = null
        val now = currentMarkers()
        val editor = editorRef.get() ?: return
        if (editor.shownLines.any { before[it] != now[it] }) editor.refreshGutter()
    }

    private fun currentMarkers(): Map<Int, LineMarker> =
        markers ?: run {
            val (diagnostics, text) = source?.invoke() ?: (emptyList<PlacedDiagnostic>() to "")
            val lines = if (diagnostics.isEmpty()) null else LineIndex(text)
            lineMarkers(diagnostics) { offset -> lines?.lineOf(offset) ?: 0 }
        }.also {
            markers = it
            lastMarkers = it
        }

    private fun column(paragraph: Int): Node? {
        val marker = currentMarkers()[paragraph] ?: return null
        return severityIcon(marker.severity).apply {
            Tooltip.install(
                this,
                Tooltip(marker.messages.joinToString("\n")).apply { showDelay = Duration.millis(300.0) },
            )
        }
    }
}

/** A small icon of [severity] (style classes `diagnostic-icon` and the severity's), coloured by the stylesheet. */
internal fun severityIcon(severity: Severity): Region =
    Region().apply {
        styleClass += "diagnostic-icon"
        severity.styleClass?.let { styleClass += "$it-icon" }
    }
