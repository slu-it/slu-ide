package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import javafx.scene.Node
import javafx.scene.control.Label
import java.nio.file.Path

/** The colors saved in the project and the palettes to pick from. */
internal class PaletteSection(
    private val state: ColoringState,
    private val savedColors: SavedColors,
    private val projectFolder: () -> Path?,
) {
    val node: Node = Label("Palette").apply { styleClass += "coloring-section-title" }

    /** Releases the listeners on the window's state. */
    fun dispose() {}
}
