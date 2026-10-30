package dev.sluide.codecs.ui

import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.conversion.ConversionModel
import dev.sluide.codecs.conversion.Side
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignI

/** The right side: the encoded text (hash rows for Hash), its status and the hint to switch the codec. */
internal class EncodedSide(
    private val model: ConversionModel,
) {
    private val area =
        PasteAwareTextArea().apply {
            promptText = "Encoded"
            isWrapText = true
        }
    private val hashRows = HashRows(model)
    private val title = Label().apply { styleClass += "codecs-side-title" }
    private val status = StatusLabel(::select)
    private val hintText = Label()
    private val hintButton = Button().apply { setOnAction { model.applyHint() } }
    private val hintRow =
        HBox(8.0, FontIcon(MaterialDesignI.INFORMATION_OUTLINE), hintText, hintButton).apply {
            styleClass += "codecs-hint"
            alignment = Pos.CENTER_LEFT
        }
    private var updating = false

    val node: VBox =
        VBox(
            6.0,
            HBox(
                6.0,
                title,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                Button("Copy").apply { setOnAction { copy() } },
                Button("Clear").apply { setOnAction { model.clear(Side.ENCODED) } },
            ).apply { alignment = Pos.CENTER_LEFT },
            StackPane(area, hashRows.node).apply { VBox.setVgrow(this, Priority.ALWAYS) },
            status,
            hintRow,
        ).apply {
            styleClass += "codecs-side"
            padding = Insets(8.0)
        }

    init {
        // A listener, not subscribe: that would report the current text as an edit.
        area.textProperty().addListener { _, _, text ->
            if (!updating) model.editEncoded(text, area.pasting)
        }
        refresh()
    }

    private fun copy() {
        copyToClipboard(
            if (model.settings.codec == CodecId.HASH) {
                model.hashes.joinToString("\n") { "${it.algorithm.title} ${it.value}" }
            } else {
                area.text
            },
        )
    }

    /** Selects the character at [position] of the encoded text and focuses it. */
    private fun select(position: Int) {
        if (!area.isVisible) return
        val at = position.coerceIn(0, area.length)
        area.requestFocus()
        area.selectRange(at, minOf(at + 1, area.length))
    }

    fun refresh() {
        val hash = model.settings.codec == CodecId.HASH
        title.text = model.settings.codec.title
        updating = true
        try {
            if (area.text != model.encoded) area.text = model.encoded
        } finally {
            updating = false
        }
        area.isEditable = !hash
        area.isVisible = !hash
        hashRows.node.isVisible = hash
        if (hash) hashRows.refresh()
        status.show(model.encodedStatus, warning = model.jwt?.expired == true)
        val hint = model.hint
        hintRow.isVisible = hint != null
        hintRow.isManaged = hint != null
        if (hint != null) {
            hintText.text = hint.message
            hintButton.text = hint.action
        }
    }
}
