package dev.sluide.coloring.ui

import dev.sluide.coloring.color.ColorFormatter
import javafx.scene.Node
import javafx.scene.control.Label

/** The input field for the current color in any CSS notation. */
internal class InputSection(
    private val state: ColoringState,
) {
    private val label = Label().apply { styleClass += "coloring-section-title" }
    val node: Node = label

    init {
        label.textProperty().bind(state.current.map(ColorFormatter::hex))
    }

    /** Releases the listeners on the window's state. */
    fun dispose() = label.textProperty().unbind()
}
