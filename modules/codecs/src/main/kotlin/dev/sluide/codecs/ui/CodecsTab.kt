package dev.sluide.codecs.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Codecs tool shown as an editor tab. */
internal class CodecsTab(
    private val view: CodecsView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Codecs"
        }
    override val tooltip: String = "Encode and decode text"
    override val content: Node get() = view.node
    override val closableByShortcut: Boolean = true

    override fun dispose() = view.dispose()
}
