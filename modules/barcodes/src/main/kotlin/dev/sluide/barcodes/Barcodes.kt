package dev.sluide.barcodes

import dev.sluide.barcodes.editor.ShowSelectionExtension
import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.ui.BarcodesTab
import dev.sluide.barcodes.ui.BarcodesView
import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.CodeEditorExtension
import javafx.stage.FileChooser
import javafx.stage.Window
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path

/**
 * The Barcodes tool of one window (ADR 0174): creates its tab and keeps the format and options, so that they outlive
 * the tab (ADR 0110); closing the tab empties the text and drops what was read. The `ide` module sets [showTab] and
 * [openUrl].
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

    /** The window's text, format, options and read image; outlives the tab, whose closing empties the text. */
    internal val model = BarcodeModel(store)

    /** A new Barcodes tab on [model]. */
    fun createTab(): EditorTab =
        BarcodesTab(
            model,
            BarcodesView(model, ::chooseSaveFile, ::chooseImageFile) {
                openUrl(it)
            },
        )

    /** Adds "Show Selection as Barcode" to the code editors' context menu (ADR 0174); registered by ide. */
    val editorExtension: CodeEditorExtension = ShowSelectionExtension(::open)

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
