package dev.sluide.barcodes.ui

import javafx.scene.Cursor
import javafx.scene.control.Label

/**
 * The line under a side: muted text, [error] in the error style, [warning] in the warning style. A click on an error
 * with a position calls [onErrorClick] with the 0-based character index.
 */
internal class StatusLabel(
    private val onErrorClick: (Int) -> Unit = {},
) : Label() {
    private var position: Int? = null

    init {
        styleClass += "barcodes-status"
        isWrapText = true
        setOnMouseClicked { position?.let(onErrorClick) }
    }

    fun info(text: String?) = show(text, null, null)

    fun warning(text: String) = show(text, null, "barcodes-warning")

    fun error(
        message: String,
        charIndex: Int?,
    ) = show(message, charIndex, "barcodes-error")

    private fun show(
        text: String?,
        charIndex: Int?,
        style: String?,
    ) {
        styleClass.removeAll("barcodes-error", "barcodes-warning")
        style?.let { styleClass += it }
        position = charIndex
        this.text = text ?: ""
        cursor = if (charIndex != null) Cursor.HAND else Cursor.DEFAULT
    }
}
