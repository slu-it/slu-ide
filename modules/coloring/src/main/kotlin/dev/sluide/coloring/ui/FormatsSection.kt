package dev.sluide.coloring.ui

import dev.sluide.coloring.color.ColorFormatter
import javafx.scene.Node
import javafx.scene.control.Label

/** The current color in the CSS notations, each with a copy button. */
internal class FormatsSection(
    private val state: ColoringState,
) {
    private val label = Label().apply { styleClass += "coloring-section-title" }
    val node: Node = label

    init {
        label.textProperty().bind(state.current.map { "Formats: " + ColorFormatter.hex(it) })
    }

    /** Releases the listeners on the window's state. */
    fun dispose() = label.textProperty().unbind()
}
