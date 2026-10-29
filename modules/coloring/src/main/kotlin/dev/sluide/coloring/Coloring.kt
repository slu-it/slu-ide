package dev.sluide.coloring

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.editor.ColorEditorExtension
import dev.sluide.coloring.ui.ColoringState
import dev.sluide.coloring.ui.ColoringTab
import dev.sluide.coloring.ui.ColoringView
import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.CodeEditorExtension
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The Coloring tool of one window (ADR 0109): creates its tab and keeps the colors it works on, so that they outlive
 * the tab. The `ide` module binds [projectFolder] and sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Coloring(
    private val savedColors: SavedColors,
) {
    /** The window's project folder, for the saved colors; bound by ide. */
    val projectFolder: ObjectProperty<Path?> = SimpleObjectProperty(null)

    /** Shows the Coloring tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The current color and the second color of the window's tool; outlives the tab. */
    internal val state = ColoringState()

    /** A new Coloring tab on [state]. */
    fun createTab(): EditorTab = ColoringTab(ColoringView(state, savedColors, projectFolder))

    /** Extends the window's code editors with color swatches and "Open in Coloring" (ADR 0108, ADR 0109). */
    val editorExtension: CodeEditorExtension = ColorEditorExtension(::show)

    /** Makes [color] current and shows the tab (from the editor's swatches). */
    fun show(color: CssColor) {
        state.current.set(color)
        showTab()
    }
}
