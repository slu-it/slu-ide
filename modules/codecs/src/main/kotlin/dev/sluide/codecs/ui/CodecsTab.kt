package dev.sluide.codecs.ui

import dev.sluide.codecs.conversion.ConversionModel
import dev.sluide.codecs.conversion.Side
import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Codecs tool shown as an editor tab. Closing it empties both sides; codec and options stay. */
internal class CodecsTab(
    private val model: ConversionModel,
    private val view: CodecsView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Codecs"
        }
    override val tooltip: String = "Encode and decode text"
    override val content: Node get() = view.node

    override fun dispose() {
        view.dispose()
        model.clear(Side.TEXT)
    }
}
