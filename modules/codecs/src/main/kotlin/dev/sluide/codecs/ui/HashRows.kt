package dev.sluide.codecs.ui

import dev.sluide.codecs.codec.HashValue
import dev.sluide.codecs.conversion.ConversionModel
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/** The encoded side of Hash: one row per algorithm with a selectable value and a copy button. */
internal class HashRows(
    private val model: ConversionModel,
) {
    private val rows = VBox(6.0).apply { padding = Insets(8.0) }
    private var shown: Pair<List<HashValue>, Boolean>? = null

    val node: ScrollPane =
        ScrollPane(rows).apply {
            styleClass += "codecs-hash-rows"
            isFitToWidth = true
        }

    fun refresh() {
        val hmac =
            model.settings.hash.key
                .isNotEmpty()
        val now = model.hashes to hmac
        if (now == shown) return
        shown = now
        rows.children.clear()
        if (model.hashes.isEmpty()) return
        if (hmac) rows.children += Label("HMAC").apply { styleClass += "codecs-group" }
        for (hash in model.hashes) rows.children += row(hash)
    }

    private fun row(hash: HashValue) =
        HBox(
            8.0,
            Label(hash.algorithm.title).apply {
                styleClass += "codecs-hash-name"
                minWidth = NAME_WIDTH
            },
            TextField(hash.value).apply {
                isEditable = false
                styleClass += "codecs-hash-value"
                HBox.setHgrow(this, Priority.ALWAYS)
            },
            copyButton { hash.value },
        ).apply { alignment = Pos.CENTER_LEFT }

    private companion object {
        const val NAME_WIDTH = 80.0
    }
}
