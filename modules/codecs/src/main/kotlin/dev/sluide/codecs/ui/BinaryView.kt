package dev.sluide.codecs.ui

import dev.sluide.codecs.binary.HexDump
import dev.sluide.codecs.conversion.ConversionModel
import dev.sluide.codecs.conversion.Status
import dev.sluide.codecs.conversion.TextContent
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/** What the text side shows for binary data: what it is, Save as and Edit as Text, and the hex dump. */
internal class BinaryView(
    private val model: ConversionModel,
    save: () -> Unit,
) {
    private val title = Label().apply { styleClass += "codecs-binary-title" }
    private val dump = TextArea().apply { isEditable = false }
    private var shown: TextContent.Bytes? = null

    val node: VBox =
        VBox(
            6.0,
            HBox(
                8.0,
                title,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                Button("Save as…").apply {
                    id = "codecs-binary-save"
                    setOnAction { save() }
                },
                Button("Edit as Text").apply {
                    id = "codecs-binary-edit"
                    setOnAction { model.editBytesAsText() }
                },
            ).apply { alignment = Pos.CENTER_LEFT },
            dump.also { VBox.setVgrow(it, Priority.ALWAYS) },
        ).apply { styleClass += "codecs-binary" }

    /** The dump is built again only for other bytes. */
    fun refresh(bytes: TextContent.Bytes) {
        (model.textStatus as? Status.Info)?.let { title.text = it.text }
        if (bytes != shown) {
            shown = bytes
            dump.text = HexDump.format(bytes.bytes)
        }
    }
}
