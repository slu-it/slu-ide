package dev.sluide.codeintelligence.diagnostics

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.TextChange
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The gutter column of the diagnostics: one per editor, and the shown rows are created again only when the marker of
 * a shown line changed (`refreshGutter` rebuilds every shown row).
 */
class DiagnosticsGutterTest {
    /** Ten lines of "x", lines 0 to 4 on screen. */
    private val editor = GutterEditor((1..10).joinToString("\n") { "x" })
    private val gutter = DiagnosticsGutter(editor)
    private var diagnostics = emptyList<PlacedDiagnostic>()

    private fun on(
        line: Int,
        severity: DiagnosticSeverity,
        message: String = "m",
    ) = PlacedDiagnostic(Diagnostic(Range(), message).apply { this.severity = severity }, line * 2, line * 2 + 1)

    @Test
    fun `the rows are created again only when a shown line's marker changes`() {
        gutter.show { diagnostics to editor.text }
        assertEquals(1, editor.columns, "one column")
        assertEquals(0, editor.refreshes, "nothing to show yet")

        diagnostics = listOf(on(1, DiagnosticSeverity.Error))
        gutter.changed()
        assertEquals(1, editor.refreshes)

        gutter.changed()
        assertEquals(1, editor.refreshes, "the same markers")

        diagnostics = listOf(on(1, DiagnosticSeverity.Error), on(8, DiagnosticSeverity.Warning))
        gutter.changed()
        assertEquals(1, editor.refreshes, "a line off screen")

        diagnostics =
            listOf(on(1, DiagnosticSeverity.Error), on(2, DiagnosticSeverity.Hint), on(8, DiagnosticSeverity.Warning))
        gutter.changed()
        assertEquals(1, editor.refreshes, "a hint has no marker")

        diagnostics = listOf(on(1, DiagnosticSeverity.Warning))
        gutter.changed()
        assertEquals(2, editor.refreshes, "line 1 became a warning")

        gutter.show(null)
        assertEquals(3, editor.refreshes, "unbound: the marker goes")
    }

    /** An editor with [text], lines 0 to 4 shown, that counts gutter columns and refreshes. */
    private class GutterEditor(
        override val text: String,
    ) : ExtensibleCodeEditor {
        var columns = 0
        var refreshes = 0

        override val file: ObservableValue<Path> = SimpleObjectProperty(Path.of("a.py"))
        override val lineCount get() = text.split('\n').size
        override val shownLines = 0..4

        override fun lineText(line: Int) = text.split('\n')[line]

        override fun onShownLinesChanged(action: () -> Unit) = Subscription {}

        override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) = Unit

        override fun addGutterColumn(
            width: Double,
            column: (paragraph: Int) -> Node?,
        ) {
            columns++
        }

        override fun refreshGutter() {
            refreshes++
        }

        override fun onTextChange(listener: (TextChange) -> Unit): AutoCloseable = AutoCloseable {}
    }
}
