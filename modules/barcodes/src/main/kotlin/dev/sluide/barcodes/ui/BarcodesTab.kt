package dev.sluide.barcodes.ui

import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/**
 * The Barcodes tool shown as an editor tab. Closing it empties the text and drops a read image; format and options
 * stay.
 */
internal class BarcodesTab(
    private val model: BarcodeModel,
    private val view: BarcodesView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Barcodes"
        }
    override val tooltip: String = "Make and read barcodes and QR codes"
    override val content: Node get() = view.node

    override fun dispose() {
        view.dispose()
        model.editText("")
    }
}
