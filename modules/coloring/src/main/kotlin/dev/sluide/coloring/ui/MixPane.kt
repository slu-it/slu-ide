package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.control.Label

/** The mix of the current color with a second color. */
internal class MixPane(
    private val state: ColoringState,
) {
    val node: Node = Label("Mix").apply { styleClass += "coloring-section-title" }

    /** Releases the listeners on the window's state. */
    fun dispose() {}
}
