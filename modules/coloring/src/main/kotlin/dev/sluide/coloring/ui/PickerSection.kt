package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.control.Label

/** The color picker for the current color. */
internal class PickerSection(
    private val state: ColoringState,
) {
    val node: Node = Label("Picker").apply { styleClass += "coloring-section-title" }

    /** Releases the listeners on the window's state. */
    fun dispose() {}
}
