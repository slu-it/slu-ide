package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.control.Label

/** A gradient between colors, as CSS. */
internal class GradientPane(
    private val state: ColoringState,
) {
    val node: Node = Label("Gradient").apply { styleClass += "coloring-section-title" }

    /** Releases the listeners on the window's state. */
    fun dispose() {}
}
