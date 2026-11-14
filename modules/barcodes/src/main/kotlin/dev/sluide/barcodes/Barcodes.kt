package dev.sluide.barcodes

import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.ui.BarcodesTab
import dev.sluide.barcodes.ui.BarcodesView
import dev.sluide.editorarea.EditorTab
import javafx.stage.FileChooser
import javafx.stage.Window
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path

/**
 * The Barcodes tool of one window (ADR 0174): creates its tab and keeps the text, the options and what was read, so
 * that they outlive the tab (ADR 0110). The `ide` module sets [showTab] and [openUrl].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Barcodes(
    store: BarcodeOptionsStore,
) {
    /** Shows the Barcodes tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** Opens a web page in the system browser; set by ide. */
    var openUrl: (URI) -> Unit = {}

    /** The text, format, options and read image of the window's tool; outlives the tab. */
    internal val model = BarcodeModel(store)

    /** A new Barcodes tab on [model]. */
    fun createTab(): EditorTab = BarcodesTab(BarcodesView(model, ::chooseSaveFile, ::chooseImageFile))

    /** Puts [text] into the tool as typed text and shows the tab. */
    fun open(text: String) {
        model.editText(text)
        showTab()
    }

    private fun chooseImageFile(window: Window?): Path? =
        FileChooser()
            .apply {
                title = "Open Image"
                extensionFilters.add(
                    FileChooser.ExtensionFilter(
                        "Images (*.png, *.jpg, *.jpeg, *.gif, *.bmp)",
                        "*.png",
                        "*.jpg",
                        "*.jpeg",
                        "*.gif",
                        "*.bmp",
                    ),
                )
            }.showOpenDialog(window)
            ?.toPath()

    private fun chooseSaveFile(
        window: Window?,
        suggestedName: String,
    ): Path? =
        FileChooser()
            .apply {
                title = "Save as"
                initialFileName = suggestedName
                extensionFilters.addAll(
                    FileChooser.ExtensionFilter("PNG image (*.png)", "*.png"),
                    FileChooser.ExtensionFilter("SVG image (*.svg)", "*.svg"),
                )
            }.showSaveDialog(window)
            ?.toPath()
}
