package dev.sluide.converting.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Converting tool shown as an editor tab. */
internal class ConvertingTab(
    private val view: ConvertingView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Converting"
        }
    override val tooltip: String = "Convert between data formats"
    override val content: Node get() = view.node
    override val closableByShortcut: Boolean = true

    override fun dispose() = view.dispose()
}
