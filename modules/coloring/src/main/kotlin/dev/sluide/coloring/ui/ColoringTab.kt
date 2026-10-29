package dev.sluide.coloring.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Coloring tool shown as an editor tab. */
internal class ColoringTab(
    private val view: ColoringView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Coloring"
        }
    override val tooltip: String = "Color tools"
    override val content: Node get() = view.node
    override val closableByShortcut: Boolean = true

    override fun dispose() = view.dispose()
}
