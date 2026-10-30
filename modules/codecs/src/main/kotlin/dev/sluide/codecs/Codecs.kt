package dev.sluide.codecs

import dev.sluide.codecs.conversion.ConversionModel
import dev.sluide.codecs.editor.OpenSelectionExtension
import dev.sluide.codecs.ui.CodecsTab
import dev.sluide.codecs.ui.CodecsView
import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.CodeEditorExtension
import javafx.stage.FileChooser
import javafx.stage.Window
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The Codecs tool of one window (ADR 0112): creates its tab and keeps the conversion state, so that it outlives the
 * tab (ADR 0110). The `ide` module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Codecs {
    /** Shows the Codecs tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The two sides and the settings of the window's tool; outlives the tab. */
    internal val model = ConversionModel()

    /** A new Codecs tab on [model]. */
    fun createTab(): EditorTab = CodecsTab(CodecsView(model, ::chooseFile, ::chooseSaveFile))

    /** Extends the window's code editors with "Open Selection in Codecs" (ADR 0112). */
    val editorExtension: CodeEditorExtension = OpenSelectionExtension(::open)

    /** Puts [text] into the tool (the detected codec's encoded side, else the text side) and shows the tab. */
    fun open(text: String) {
        model.open(text)
        showTab()
    }

    private fun chooseFile(window: Window?): Path? =
        FileChooser()
            .apply {
                title = "Open File"
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
            }.showSaveDialog(window)
            ?.toPath()
}
