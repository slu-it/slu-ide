package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.control.Label

/** Shades, tints and color harmonies of the current color. */
internal class HarmoniesPane(
    private val state: ColoringState,
) {
    val node: Node = Label("Shades & Harmonies").apply { styleClass += "coloring-section-title" }

    /** Releases the listeners on the window's state. */
    fun dispose() {}
}
