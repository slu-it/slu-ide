package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.Export
import dev.sluide.barcodes.engine.grouped
import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.model.Source
import dev.sluide.barcodes.payload.Payloads
import dev.sluide.fileviewers.WrappingToolbar
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TextArea
import javafx.scene.control.Tooltip
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DragEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import java.io.IOException
import java.net.URI
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
    private val chooseImageFile: (Window?) -> Path?,
    private val openUrl: (URI) -> Unit = {},
) {
    private var updating = false

    /** An error of Copy or Save as…, shown until the next model change. */
    private var localImageStatus: String? = null

    /** A note after a read that was applied (several files dropped), appended to the status until the next change. */
    private var readNote: String? = null

    private val input =
        ImageInput(
            model,
            reading = { showLocal("Reading…") },
            noted = {
                readNote = it
                showStatuses()
            },
        )

    private val options = OptionsBar(model)
    private val structured = StructuredView()
    private val area =
        TextArea().apply {
            id = "barcodes-text"
            promptText = "Text"
        }
    private val textStatus = StatusLabel(::select).apply { id = "barcodes-text-status" }
    private val imageStatus = StatusLabel().apply { id = "barcodes-image-status" }
    private val codeCanvas = CodeCanvas().apply { onPick = { model.selectCode(it) } }
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

    private val pasteImage =
        Button("Paste Image").apply {
            id = "barcodes-paste-image"
            isFocusTraversable = false
            setOnAction { input.readClipboard() }
        }
    private val openImage =
        Button("Open…").apply {
            id = "barcodes-open-image"
            isFocusTraversable = false
            setOnAction { chooseImageFile(root.scene?.window)?.let { input.readFile(it) } }
        }

    /** The buttons above the canvas; Paste Image and Open… go in front of Copy. */
    internal val imageButtons =
        HBox(6.0, pasteImage, openImage, copyImage, saveImage).apply { alignment = Pos.CENTER_LEFT }

    private var browserUri: URI? = null

    /** Opens the text in the browser; shown only while the text is one http(s) URL, and never clicked by the app. */
    private val openInBrowser =
        Button("Open in Browser").apply {
            id = "barcodes-open-browser"
            graphic = FontIcon("mdi2o-open-in-new")
            isFocusTraversable = false
            setOnAction { browserUri?.let(openUrl) }
        }

    /** The toolbar spot of the text side; holds Open in Browser. */
    internal val textToolbarSlot = HBox(6.0, openInBrowser).apply { alignment = Pos.CENTER_LEFT }

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

    /** Whether the canvas holds the bitmap of a read image. */
    internal val holdsPicture get() = codeCanvas.holdsPicture

    /** How many bitmaps the canvas built for read images; tests check that drawing again does not build one. */
    internal val picturesBuilt get() = codeCanvas.picturesBuilt

    private val removeListener: () -> Unit

    init {
        // A listener, not subscribe: that would report the current text as an edit.
        area.textProperty().addListener { _, _, text ->
            if (!updating) model.editText(text)
        }
        // Shortcut+V reads an image from the clipboard only when it holds no text; otherwise the TextArea pastes as usual.
        root.addEventFilter(KeyEvent.KEY_PRESSED) {
            if (pasteKeys.match(it)) {
                val clipboard = Clipboard.getSystemClipboard()
                if (clipboard.hasImage() && !clipboard.hasString()) {
                    input.readClipboard()
                    it.consume()
                }
            }
        }
        removeListener =
            model.addListener {
                localImageStatus = null
                readNote = null
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
            structured.limitHeight(heightProperty())
        }
    }

    private fun imageSide(): VBox {
        val bar = WrappingToolbar().apply { leading.setAll(imageButtons) }
        val pane =
            StackPane(codeCanvas, hint).apply {
                setOnDragOver(::dragOver)
                setOnDragDropped(::dropped)
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
        showStructured()
        browserUri = Payloads.httpUrl(model.text)
        openInBrowser.isVisible = browserUri != null
        openInBrowser.isManaged = browserUri != null
        val generated = model.generated
        val failure = model.failure
        codeCanvas.content =
            when {
                model.source == Source.IMAGE && model.image != null -> {
                    CanvasContent.Picture(checkNotNull(model.image), model.codes, model.selectedCode)
                }

                generated != null -> {
                    CanvasContent.Generated(generated, model.options, dimmed = failure != null)
                }

                else -> {
                    CanvasContent.Empty
                }
            }
        hint.isVisible = codeCanvas.content == CanvasContent.Empty && model.source == Source.TEXT && failure == null
        // A failure keeps the last code on screen, which is not the code of the text: nothing to export then.
        val canExport = generated != null && failure == null && model.source == Source.TEXT
        copyImage.isDisable = !canExport
        saveImage.isDisable = !canExport
        val why =
            if (model.source ==
                Source.IMAGE
            ) {
                Tooltip("Edit the text or change an option to make a code")
            } else {
                null
            }
        copyImage.tooltip = why
        saveImage.tooltip = why
        showStatuses()
    }

    private fun showStructured() {
        val shown = structured.show(model.text)
        if (shown && structured.node !in structuredSlot.children) {
            structuredSlot.children += structured.node
        } else if (!shown) {
            structuredSlot.children.clear()
        }
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
        val pick = if (model.source == Source.IMAGE && model.codes.size > 1) " · click an outline to pick one" else ""
        val message =
            localImageStatus ?: model.imageStatus?.let { it + pick + (readNote?.let { note -> " · $note" } ?: "") }
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

    private fun dragOver(event: DragEvent) {
        if (event.dragboard.hasFiles() || event.dragboard.hasImage()) event.acceptTransferModes(TransferMode.COPY)
        event.consume()
    }

    private fun dropped(event: DragEvent) {
        val board = event.dragboard
        val files = if (board.hasFiles()) board.files.orEmpty() else emptyList()
        when {
            files.isNotEmpty() -> {
                input.readFile(
                    files.first().toPath(),
                    if (files.size > 1) "only the first of ${files.size} files was read" else null,
                )
            }

            board.hasImage() -> {
                input.readBoard(board, "dropped image", "The dropped item holds no image.")
            }

            else -> {
                event.consume()
                return
            }
        }
        event.isDropCompleted = true
        event.consume()
    }

    private fun showLocal(message: String) {
        localImageStatus = message
        showStatuses()
    }

    /** The view stops listening; the model outlives it. */
    fun dispose() = removeListener()

    internal companion object {
        private val pasteKeys = KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN)

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
