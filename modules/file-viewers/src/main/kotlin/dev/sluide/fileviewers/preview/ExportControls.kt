package dev.sluide.fileviewers.preview

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignE
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The Export menu of a preview's toolbar: "HTML" writes [export]'s page of the saved text beside the file. An existing
 * target is only replaced after asking. The page is made off the FX thread (it may download images); the button is
 * disabled meanwhile. Problems and images that could not be embedded are reported in a dialog; a clean export is not.
 * Everything but the rendering and writing runs on the FX thread.
 */
internal class ExportControls(
    private val export: HtmlExport,
    private val file: () -> Path,
    private val savedText: () -> String,
    private val owner: () -> Window?,
) {
    val button =
        MenuButton(null, FontIcon(MaterialDesignE.EXPORT_VARIANT).apply { iconSize = ICON_SIZE }).apply {
            styleClass += "preview-export"
            tooltip = Tooltip("Export")
            // A click leaves the focus where it is.
            isFocusTraversable = false
            items += MenuItem("HTML").apply { setOnAction { exportHtml() } }
        }

    /** Asks whether to replace the existing [target]; tests replace it, since it blocks. */
    var confirmOverwrite: (Path) -> Boolean = ::askToOverwrite

    /** Tells the user [message]; tests replace it, since it blocks. */
    var report: (String) -> Unit = ::showReport

    /** Writes the HTML export of the saved text, after asking if the target exists. */
    fun exportHtml() {
        val source = file()
        val target = export.target(source)
        if (target.exists() && !confirmOverwrite(target)) return
        val text = savedText()
        button.isDisable = true
        Thread.ofVirtual().name("sluide-export").start {
            var message: String? = null
            try {
                message =
                    try {
                        val page = export.render(source, text)
                        Files.writeString(target, page.html)
                        if (page.notEmbedded.isEmpty()) {
                            null
                        } else {
                            "Exported ${target.fileName}, but these images could not be embedded:\n" +
                                page.notEmbedded.joinToString("\n")
                        }
                    } catch (e: Exception) {
                        "Could not export ${source.fileName}: ${e.message ?: e.javaClass.simpleName}"
                    }
            } finally {
                // Also after an error, so that the button never stays disabled.
                Platform.runLater {
                    button.isDisable = false
                    message?.let(report)
                }
            }
        }
    }

    private fun askToOverwrite(target: Path): Boolean {
        val name = target.fileName
        val replace = ButtonType("Replace", ButtonBar.ButtonData.OK_DONE)
        val alert =
            Alert(
                Alert.AlertType.WARNING,
                "$name already exists in this folder. Replace it with the export?",
                replace,
                ButtonType.CANCEL,
            ).apply {
                owner()?.let(::initOwner)
                title = "Export"
                headerText = "Replace $name?"
                val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
                (dialogPane.lookupButton(replace) as Button).isDefaultButton = false
                cancel.isDefaultButton = true
                // Later, since the dialog focuses a button of its own once shown.
                setOnShown { Platform.runLater(cancel::requestFocus) }
            }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == replace
    }

    private fun showReport(message: String) {
        Alert(Alert.AlertType.WARNING, message, ButtonType.OK)
            .apply {
                owner()?.let(::initOwner)
                title = "Export"
                headerText = null
            }.show()
    }

    private companion object {
        const val ICON_SIZE = 14
    }
}
