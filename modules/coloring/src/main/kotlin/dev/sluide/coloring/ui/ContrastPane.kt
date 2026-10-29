package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.control.Label

/** The contrast of the current color against a second color, by WCAG and APCA. */
internal class ContrastPane(
    private val state: ColoringState,
) {
    val node: Node = Label("Contrast").apply { styleClass += "coloring-section-title" }

    /** Releases the listeners on the window's state. */
    fun dispose() {}
}
