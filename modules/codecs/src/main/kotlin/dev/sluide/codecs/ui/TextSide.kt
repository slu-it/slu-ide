package dev.sluide.codecs.ui

import dev.sluide.codecs.binary.HexDump
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.conversion.ConversionModel
import dev.sluide.codecs.conversion.Side
import dev.sluide.codecs.conversion.Status
import dev.sluide.codecs.conversion.TextContent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.input.TransferMode
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The left side: the text (or the binary view), the file buttons and its status; takes dropped files. */
internal class TextSide(
    private val model: ConversionModel,
    private val chooseFile: (Window?) -> Path?,
    private val chooseSaveFile: (Window?, String) -> Path?,
) {
    private val area = TextArea().apply { promptText = "Text" }
    private val binary = BinaryView(model, ::saveAs)
    private val status = StatusLabel(::select)
    private val saveButton =
        Button("Save as…").apply {
            id = "codecs-save"
            setOnAction { saveAs() }
        }
    private var updating = false

    val node: VBox =
        VBox(
            6.0,
            header(),
            StackPane(area, binary.node).apply { VBox.setVgrow(this, Priority.ALWAYS) },
            status,
        ).apply {
            styleClass += "codecs-side"
            padding = Insets(8.0)
            setOnDragOver {
                if (it.dragboard.hasFiles()) it.acceptTransferModes(TransferMode.COPY)
                it.consume()
            }
            setOnDragDropped {
                val files = if (it.dragboard.hasFiles()) it.dragboard.files else emptyList()
                filesDropped(files)
                it.isDropCompleted = files.isNotEmpty()
                it.consume()
            }
        }

    init {
        // A listener, not subscribe: that would report the current text as an edit.
        area.textProperty().addListener { _, _, text ->
            if (updating) return@addListener
            model.editText(text)
        }
        refresh()
    }

    private fun header() =
        HBox(
            6.0,
            Label("Text").apply { styleClass += "codecs-side-title" },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            Button("Open File…").apply { setOnAction { openFile() } },
            saveButton,
            Button("Copy").apply { setOnAction { copyText() } },
            Button("Clear").apply { setOnAction { model.clear(Side.TEXT) } },
        ).apply { alignment = Pos.CENTER_LEFT }

    /** Opens the first of [files]; an error shows in the status until the next change. */
    fun filesDropped(files: List<File>) {
        val file = files.firstOrNull() ?: return
        open(file.toPath())
    }

    private fun openFile() {
        val path = chooseFile(node.scene?.window) ?: return
        open(path)
    }

    private fun open(path: Path) {
        model.openFile(path)?.let(::showError)
    }

    private fun saveAs() {
        val bytes = model.bytesToSave() ?: return
        val suggested = model.fileName ?: if (model.text is TextContent.Bytes) "decoded.bin" else "decoded.txt"
        val path = chooseSaveFile(node.scene?.window, suggested) ?: return
        try {
            Files.write(path, bytes)
        } catch (e: IOException) {
            showError(e.message ?: e.toString())
        }
    }

    /** Shows an error of this side (not the model's) until the next model change, also over binary content. */
    private fun showError(message: String) {
        status.show(Status.Error(message, null))
        status.isVisible = true
        status.isManaged = true
    }

    private fun copyText() {
        val content = model.text
        copyToClipboard(if (content is TextContent.Bytes) HexDump.format(content.bytes) else area.text)
    }

    /** Selects the character at [position] of the text and focuses it. */
    private fun select(position: Int) {
        if (!area.isVisible) return
        val at = position.coerceIn(0, area.length)
        area.requestFocus()
        area.selectRange(at, minOf(at + 1, area.length))
    }

    fun refresh() {
        val content = model.text
        val jwt = model.settings.codec == CodecId.JWT
        val shown =
            when {
                jwt -> model.jwt?.summary ?: ""
                content is TextContent.Text -> content.text
                else -> ""
            }
        updating = true
        try {
            if (area.text != shown) area.text = shown
        } finally {
            updating = false
        }
        area.isEditable = !jwt
        val bytes = content as? TextContent.Bytes
        area.isVisible = bytes == null
        binary.node.isVisible = bytes != null
        bytes?.let(binary::refresh)
        saveButton.isDisable = model.bytesToSave() == null || (content is TextContent.Text && content.text.isEmpty())
        status.show(model.textStatus)
        // The binary view's header says the same as the status line.
        val hidden = bytes != null && model.textStatus is Status.Info
        status.isVisible = !hidden
        status.isManaged = !hidden
    }
}
