package dev.sluide.barcodes.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Barcodes tool shown as an editor tab. */
internal class BarcodesTab(
    private val view: BarcodesView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Barcodes"
        }
    override val tooltip: String = "Make and read barcodes and QR codes"
    override val content: Node get() = view.node

    override fun dispose() = view.dispose()
}
