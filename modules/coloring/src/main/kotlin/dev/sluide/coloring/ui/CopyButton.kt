package dev.sluide.coloring.ui

import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

/** The small icon button of the Coloring tool that copies [text] (read when clicked) to the system clipboard. */
internal fun copyButton(
    tooltip: String = "Copy",
    text: () -> String,
): Button =
    Button().apply {
        graphic = FontIcon(MaterialDesignC.CONTENT_COPY)
        this.tooltip = Tooltip(tooltip)
        styleClass += "coloring-copy"
        setOnAction { Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text()) }) }
    }
