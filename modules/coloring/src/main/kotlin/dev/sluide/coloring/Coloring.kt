package dev.sluide.coloring

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.ui.ColoringState
import dev.sluide.coloring.ui.ColoringTab
import dev.sluide.coloring.ui.ColoringView
import dev.sluide.editorarea.EditorTab
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The Coloring tool of one window (ADR 0109): creates its tab and keeps the colors it works on, so that they outlive
 * the tab. The `ide` module sets [projectFolder] and [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Coloring(
    private val savedColors: SavedColors,
) {
    /** The window's project folder, for the saved colors; set by ide. */
    var projectFolder: () -> Path? = { null }

    /** Shows the Coloring tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The current color and the second color of the window's tool; outlives the tab. */
    internal val state = ColoringState()

    /** A new Coloring tab on [state]. */
    fun createTab(): EditorTab = ColoringTab(ColoringView(state, savedColors) { projectFolder() })

    /** Makes [color] current and shows the tab (from the editor's swatches). */
    fun show(color: CssColor) {
        state.current.set(color)
        showTab()
    }
}
