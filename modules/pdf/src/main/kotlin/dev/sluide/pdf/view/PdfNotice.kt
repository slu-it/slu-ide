package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PdfOpenResult
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.VBox

/**
 * The notice centred over the pages when there are none to show: loading, an invalid or empty document, one too large
 * to open, or one protected by a password (with a button that asks again). [pages] is hidden while it shows.
 */
internal class PdfNotice(
    private val pages: Node,
) {
    val text = Label().apply { styleClass += "pdf-notice-text" }
    val action =
        Button().apply {
            styleClass += "pdf-notice-action"
            isFocusTraversable = false
        }
    val node =
        VBox(text, action).apply {
            styleClass += "pdf-notice"
            isVisible = false
        }

    /** Whether a notice shows. */
    val shown: Boolean get() = node.isVisible

    /** Shows [message] in place of the pages, with a button [actionText] that runs [onAction] if given. */
    fun show(
        message: String,
        actionText: String? = null,
        onAction: () -> Unit = {},
    ) {
        text.text = message
        action.text = actionText
        action.isVisible = actionText != null
        action.isManaged = actionText != null
        action.setOnAction { onAction() }
        node.isVisible = true
        pages.isVisible = false
    }

    fun hide() {
        node.isVisible = false
        pages.isVisible = true
    }

    companion object {
        const val LOADING = "Loading…"
        const val PROTECTED = "This PDF is password-protected."
        const val ENTER_PASSWORD = "Enter Password…"
    }
}

/** The notice for a document that did not open: none for those that ask for a password. */
internal fun noticeText(result: PdfOpenResult): String? =
    when (result) {
        is PdfOpenResult.Invalid -> "Not a valid PDF: ${result.reason}"
        PdfOpenResult.NoPages -> "This PDF has no pages."
        PdfOpenResult.TooLarge -> "This PDF is too large to open."
        else -> null
    }
