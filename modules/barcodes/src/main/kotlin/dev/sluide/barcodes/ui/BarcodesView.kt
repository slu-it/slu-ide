package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.Export
import dev.sluide.barcodes.engine.grouped
import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.model.Source
import dev.sluide.fileviewers.WrappingToolbar
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TextArea
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Barcodes tool: the options toolbar, the text side and the image side. It shows [model] and forwards the user's
 * edits; the view owns a listener on the model until [dispose]. While the view applies the model to its controls,
 * [updating] stops their listeners from calling [BarcodeModel.editText], so a change of the app's own (a read image)
 * is no request for a generated code.
 */
internal class BarcodesView(
    private val model: BarcodeModel,
    private val chooseSaveFile: (Window?, String) -> Path?,
) {
    private var updating = false

    /** An error of Copy or Save as…, shown until the next model change. */
    private var localImageStatus: String? = null

    private val options = OptionsBar(model)
    private val area =
        TextArea().apply {
            id = "barcodes-text"
            promptText = "Text"
        }
    private val textStatus = StatusLabel(::select).apply { id = "barcodes-text-status" }
    private val imageStatus = StatusLabel().apply { id = "barcodes-image-status" }
    private val codeCanvas = CodeCanvas()
    private val hint = Label("Type text, or paste an image (Shortcut+V)").apply { styleClass += "barcodes-hint" }
    private val copyImage =
        Button("Copy").apply {
            id = "barcodes-copy-image"
            isFocusTraversable = false
            setOnAction { copyCode() }
        }
    private val saveImage =
        Button("Save as…").apply {
            id = "barcodes-save-image"
            isFocusTraversable = false
            setOnAction { saveCode() }
        }

    /** The buttons above the canvas; Paste Image and Open… go in front of Copy. */
    internal val imageButtons = HBox(6.0, copyImage, saveImage).apply { alignment = Pos.CENTER_LEFT }

    /** The toolbar spot of the text side for Open in Browser. */
    internal val textToolbarSlot = HBox(6.0).apply { alignment = Pos.CENTER_LEFT }

    /** Below the text: the structured view goes here; takes no room while empty. */
    internal val structuredSlot =
        VBox(6.0).apply { managedProperty().bind(Bindings.isNotEmpty(children)) }

    /** The tab's node; the Shortcut+V filter sits here. */
    internal val root: BorderPane =
        BorderPane().apply {
            styleClass += "barcodes"
            stylesheets +=
                checkNotNull(BarcodesView::class.java.getResource("/dev/sluide/barcodes/barcodes.css")).toExternalForm()
            top = options.node
            center = SplitPane(textSide(), imageSide()).apply { setDividerPositions(0.5) }
        }

    val node: BorderPane get() = root

    /** The canvas the code is drawn on; tests sample its pixels. */
    internal val canvas get() = codeCanvas.canvas

    private val removeListener: () -> Unit

    init {
        // A listener, not subscribe: that would report the current text as an edit.
        area.textProperty().addListener { _, _, text ->
            if (!updating) model.editText(text)
        }
        removeListener =
            model.addListener {
                localImageStatus = null
                refresh()
            }
        refresh()
    }

    private fun textSide(): VBox {
        val header =
            WrappingToolbar().apply {
                hgap = 6.0
                leading.setAll(Label("Text").apply { styleClass += "barcodes-side-title" }, textToolbarSlot)
                trailing.setAll(
                    Button("Copy").apply {
                        id = "barcodes-copy-text"
                        isFocusTraversable = false
                        setOnAction { copyText() }
                    },
                    Button("Clear").apply {
                        id = "barcodes-clear"
                        isFocusTraversable = false
                        setOnAction { model.editText("") }
                    },
                )
            }
        VBox.setVgrow(area, Priority.ALWAYS)
        return VBox(6.0, header, area, structuredSlot, textStatus).apply {
            padding = Insets(8.0)
            minWidth = 0.0
        }
    }

    private fun imageSide(): VBox {
        val bar = WrappingToolbar().apply { leading.setAll(imageButtons) }
        val pane =
            StackPane(codeCanvas, hint).apply {
                styleClass += "barcodes-canvas-pane"
                minHeight = 0.0
                VBox.setVgrow(this, Priority.ALWAYS)
            }
        return VBox(6.0, bar, pane, imageStatus).apply {
            padding = Insets(8.0)
            minWidth = 0.0
        }
    }

    private fun refresh() {
        options.show()
        updating = true
        try {
            if (area.text != model.text) area.text = model.text
        } finally {
            updating = false
        }
        val generated = model.generated
        val failure = model.failure
        codeCanvas.content =
            when {
                model.source == Source.IMAGE && model.image != null -> CanvasContent.Picture(checkNotNull(model.image))
                generated != null -> CanvasContent.Generated(generated, model.options, dimmed = failure != null)
                else -> CanvasContent.Empty
            }
        hint.isVisible = codeCanvas.content == CanvasContent.Empty && model.source == Source.TEXT && failure == null
        // A failure keeps the last code on screen, which is not the code of the text: nothing to export then.
        val canExport = generated != null && failure == null && model.source == Source.TEXT
        copyImage.isDisable = !canExport
        saveImage.isDisable = !canExport
        showStatuses()
    }

    private fun showStatuses() {
        val failure = model.failure
        val generated = model.generated
        if (failure != null) {
            textStatus.error(failure.message, failure.charIndex)
        } else if (model.text.isEmpty()) {
            textStatus.info("")
        } else {
            val chars = model.text.codePointCount(0, model.text.length)
            val bytes = model.text.toByteArray(Charsets.UTF_8).size
            val counts = "${grouped(chars)} chars · ${grouped(bytes)} bytes"
            val summary = generated?.takeIf { model.source == Source.TEXT }?.summary
            textStatus.info(if (summary != null) "$counts · $summary" else counts)
        }
        val message = localImageStatus ?: model.imageStatus
        val warning = model.colorWarning
        when {
            message != null -> imageStatus.info(message)
            warning != null -> imageStatus.warning(warning)
            else -> imageStatus.info("")
        }
    }

    /** Selects the character at [position] of the text and focuses it. */
    private fun select(position: Int) {
        val at = position.coerceIn(0, area.length)
        area.requestFocus()
        area.selectRange(at, minOf(at + 1, area.length))
    }

    private fun copyText() {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(area.text) })
    }

    private fun copyCode() {
        val code = model.generated ?: return
        try {
            val image = Export.argb(code, model.options)
            val pixels = WritableImage(image.width, image.height)
            pixels.pixelWriter.setPixels(
                0,
                0,
                image.width,
                image.height,
                PixelFormat.getIntArgbInstance(),
                image.argb,
                0,
                image.width,
            )
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putImage(pixels) })
        } catch (e: IllegalArgumentException) {
            showLocal(e.message ?: "The code is too large to copy.")
        }
    }

    private fun saveCode() {
        val code = model.generated ?: return
        val path = chooseSaveFile(root.scene?.window, suggestedName(code.format)) ?: return
        try {
            if (path.fileName.toString().endsWith(".svg", ignoreCase = true)) {
                val svg = Export.svg(code, model.options)
                writeReplacing(path) { Files.writeString(it, svg) }
            } else {
                val png = Export.png(code, model.options)
                writeReplacing(path) { Files.write(it, png) }
            }
        } catch (e: IOException) {
            showLocal(e.message ?: e.toString())
        } catch (e: IllegalArgumentException) {
            showLocal(e.message ?: "The code is too large to save.")
        }
    }

    private fun showLocal(message: String) {
        localImageStatus = message
        showStatuses()
    }

    /** The view stops listening; the model outlives it. */
    fun dispose() = removeListener()

    internal companion object {
        fun suggestedName(format: CodeFormat): String =
            when (format) {
                CodeFormat.QR_CODE -> "qr-code.png"
                CodeFormat.DATA_MATRIX -> "data-matrix.png"
                CodeFormat.AZTEC -> "aztec.png"
                CodeFormat.PDF_417 -> "pdf417.png"
                CodeFormat.CODE_128 -> "code-128.png"
                CodeFormat.EAN_13 -> "ean-13.png"
                CodeFormat.UPC_A -> "upc-a.png"
                else -> "barcode.png"
            }
    }
}
