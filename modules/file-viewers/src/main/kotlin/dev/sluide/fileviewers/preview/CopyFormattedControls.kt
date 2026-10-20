package dev.sluide.fileviewers.preview

import javafx.animation.PauseTransition
import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

/**
 * Copy Formatted for a preview that [copies][Preview.copiesFormatted] its text formatted: a toolbar [node] with an icon
 * button and a short message beside it. A click puts what [formatted] returns on the [clipboard] and shows "Copied",
 * or leaves the clipboard alone and shows "Not copied: <reason>". The message goes away after [MESSAGE_SECONDS]
 * seconds, on the next click and on [clear] (the file is rendered again). Everything runs on the FX thread.
 */
internal class CopyFormattedControls(
    private val formatted: () -> Formatted,
    private val clipboard: (String) -> Unit = ::copyToSystemClipboard,
) {
    private val icon = FontIcon(MaterialDesignC.CONTENT_COPY)

    val button =
        Button(null, icon).apply {
            styleClass += listOf("fold-button", "preview-copy-formatted")
            isFocusTraversable = false
            tooltip = Tooltip("Copy Formatted")
            setOnAction { copy() }
        }

    val message =
        Label().apply {
            styleClass += "preview-copy-message"
            textOverrun = OverrunStyle.ELLIPSIS
            visibleProperty().bind(textProperty().isNotEmpty)
            managedProperty().bind(visibleProperty())
        }

    /** Takes the message away when it ends. */
    val pause = PauseTransition(Duration.seconds(MESSAGE_SECONDS.toDouble())).apply { setOnFinished { clear() } }

    val node: Node =
        HBox(button, message).apply {
            styleClass += "copy-formatted-controls"
            alignment = Pos.CENTER_LEFT
        }

    /** Copies the formatted text, or says why it cannot. */
    fun copy() {
        when (val result = formatted()) {
            is Formatted.Text -> {
                clipboard(result.text)
                show("Copied", MaterialDesignC.CHECK, false)
            }

            is Formatted.Failed -> {
                show("Not copied: ${result.reason}", MaterialDesignC.CONTENT_COPY, true)
            }
        }
    }

    /** Takes the message away and puts the copy icon back. */
    fun clear() {
        pause.stop()
        message.text = ""
        message.tooltip = null
        message.pseudoClassStateChanged(ERROR, false)
        icon.iconCode = MaterialDesignC.CONTENT_COPY
    }

    private fun show(
        text: String,
        ikon: MaterialDesignC,
        failed: Boolean,
    ) {
        clear()
        message.text = text
        message.tooltip = Tooltip(text)
        message.pseudoClassStateChanged(ERROR, failed)
        icon.iconCode = ikon
        pause.playFromStart()
    }

    private companion object {
        const val MESSAGE_SECONDS = 3
        val ERROR: PseudoClass = PseudoClass.getPseudoClass("error")

        fun copyToSystemClipboard(text: String) {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
        }
    }
}
