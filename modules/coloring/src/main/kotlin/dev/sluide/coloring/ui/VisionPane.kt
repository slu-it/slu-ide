package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.control.Label

/** The current color as people with color vision deficiencies see it. */
internal class VisionPane(
    private val state: ColoringState,
) {
    val node: Node = Label("Vision").apply { styleClass += "coloring-section-title" }

    /** Releases the listeners on the window's state. */
    fun dispose() {}
}
