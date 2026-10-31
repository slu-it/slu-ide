package dev.sluide.converting.ui

import dev.sluide.converting.TextFormat
import dev.sluide.converting.model.ConvertingModel
import dev.sluide.converting.model.Side
import dev.sluide.converting.model.Status
import dev.sluide.converting.model.TargetChoice
import dev.sluide.fileviewers.CodeEditors
import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.OverrunStyle
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.TransferMode
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One side of the tool: the header with the format picker, Copy, Clear and the File menu, the editor of the side's
 * text, and the status line with the hint (on the source); takes dropped files. Shows [model] and forwards edits;
 * [openScratch] (null: no menu item) opens a scratch file with an extension and a text.
 */
internal class ConvertingSide(
    private val model: ConvertingModel,
    private val side: Side,
    private val editorCss: ObservableValue<String>,
    private val chooseFile: (Window?) -> Path?,
    private val chooseSaveFile: (Window?, String) -> Path?,
    private val openScratch: ((extension: String, text: String) -> Unit)?,
) {
    private var highlighted: TextFormat? = model.format(side)

    // Created with the model's text, so that a tab opened again has no undo step that empties the side.
    private val editor = CodeEditors.text(model.text(side), highlighted?.highlighter)
    private var updating = false

    /** The source's read error marked in the editor; a new one comes with every conversion that fails. */
    private var marked: Status.Failure? = null

    /** The editor offset a click on the status line selects; null while the status shows no read error. */
    private var statusOffset: Int? = null

    private val picker =
        MenuButton().apply {
            styleClass += "converting-picker"
            // The target's picker marks the formats that cannot hold the source; checked when it opens.
            setOnShowing { if (side == model.target) model.checkTargets() }
        }
    private val statusTooltip = Tooltip()
    private val status =
        Label().apply {
            styleClass += "converting-status"
            tooltip = statusTooltip
            textOverrun = OverrunStyle.ELLIPSIS
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(this, Priority.ALWAYS)
            setOnMouseClicked { statusOffset?.let(editor::select) }
        }
    private val hintLabel = Label().apply { styleClass += "converting-hint-text" }
    private val hint =
        HBox(
            6.0,
            hintLabel,
            Button("Switch").apply { setOnAction { model.applyHint() } },
        ).apply {
            styleClass += "converting-hint"
            alignment = Pos.CENTER_RIGHT
            minWidth = Region.USE_PREF_SIZE
        }
    private val copyButton = Button("Copy").apply { setOnAction { copy() } }
    private val saveItem = MenuItem("Save as…").apply { setOnAction { saveAs() } }

    val node: VBox =
        VBox(
            6.0,
            header(),
            editor.node.also { VBox.setVgrow(it, Priority.ALWAYS) },
            // The hint sits in the status line, so that both editors end at the same height.
            HBox(6.0, status, hint).apply {
                alignment = Pos.CENTER_LEFT
                minHeightProperty().bind(copyButton.heightProperty())
            },
        ).apply {
            styleClass += "converting-side"
            id = "converting-${side.name.lowercase()}"
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
        editor.node.styleProperty().bind(editorCss)
        // A listener, not subscribe: that would report the current text as an edit.
        editor.textProperty.addListener { _, _, text ->
            if (!updating) model.edit(side, text)
        }
        refresh()
    }

    private fun header(): HBox {
        val fileItems =
            listOfNotNull(
                MenuItem("Open File…").apply { setOnAction { openFile() } },
                saveItem,
                openScratch?.let { open ->
                    MenuItem("Open as Scratch").apply {
                        setOnAction { open(scratchExtension(model.format(side)), model.text(side)) }
                    }
                },
            )
        return HBox(
            6.0,
            picker,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            copyButton,
            Button("Clear").apply { setOnAction { model.edit(side, "") } },
            MenuButton("File", null, *fileItems.toTypedArray()).apply { styleClass += "converting-file" },
        ).apply { alignment = Pos.CENTER_LEFT }
    }

    /** Opens the first of [files] on this side; an error shows in the status until the next change. */
    fun filesDropped(files: List<File>) {
        val file = files.firstOrNull() ?: return
        open(file.toPath())
    }

    private fun openFile() {
        val path = chooseFile(node.scene?.window) ?: return
        open(path)
    }

    private fun open(path: Path) {
        model.openFile(side, path)?.let(::showError)
    }

    private fun saveAs() {
        val path = chooseSaveFile(node.scene?.window, model.fileName(side)) ?: return
        try {
            Files.writeString(path, model.text(side))
        } catch (e: IOException) {
            showError("Cannot save ${path.fileName ?: path}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun copy() {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(model.text(side)) })
    }

    /** Shows an error of this side (not the model's) until the next model change; a click on it does nothing. */
    private fun showError(message: String) {
        showStatus(Status.Failure(message, null), offset = null)
    }

    fun refresh() {
        val text = model.text(side)
        // The editor holds \n line breaks only; a text that differs in line breaks alone is shown already.
        if (editorText(editor.text) != editorText(text)) {
            updating = true
            try {
                editor.replaceText(text)
            } finally {
                updating = false
            }
        }
        val format = model.format(side)
        if (format !== highlighted) {
            highlighted = format
            editor.highlighter = format?.highlighter
        }
        val isSource = side == model.source
        val pick = model.pick(side)
        picker.text = pickerLabel(isSource, pick, format)
        val choices =
            if (isSource) {
                model.allFamilies.flatMap { it.formats }.map {
                    TargetChoice(
                        it,
                        null,
                    )
                }
            } else {
                model.targetChoices
            }
        val group = ToggleGroup()
        picker.items.setAll(pickerEntries(model.allFamilies, choices, pick).map { menuItem(it, group) })
        val status = model.status(side)
        val offset = clickOffset(status, text)
        showStatus(status, offset)
        markError(status as? Status.Failure, offset)
        val shownHint = model.hint?.takeIf { isSource }
        hintLabel.text = shownHint?.message.orEmpty()
        hint.isVisible = shownHint != null
        hint.isManaged = shownHint != null
        saveItem.isDisable = text.isEmpty()
    }

    /**
     * Marks the read error at [offset] in the editor, once per failed conversion: an edit clears the mark, and the
     * failure it showed must not come back before the edit is converted.
     */
    private fun markError(
        failure: Status.Failure?,
        offset: Int?,
    ) {
        if (failure == null || offset == null) {
            marked = null
            editor.markError(null)
        } else if (failure !== marked) {
            marked = failure
            editor.markError(offset)
        }
    }

    private fun showStatus(
        status: Status,
        offset: Int?,
    ) {
        statusOffset = offset
        this.status.text = status.text
        statusTooltip.text = status.text
        this.status.styleClass.remove("converting-error")
        if (status is Status.Failure) this.status.styleClass += "converting-error"
        this.status.cursor = if (offset != null) Cursor.HAND else Cursor.DEFAULT
    }

    private fun menuItem(
        entry: PickerEntry,
        group: ToggleGroup,
    ) = when (entry) {
        is PickerEntry.Header -> {
            MenuItem(entry.title).apply {
                styleClass += "converting-picker-header"
                isDisable = true
            }
        }

        is PickerEntry.Item -> {
            RadioMenuItem(entry.text).apply {
                toggleGroup = group
                isSelected = entry.selected
                isDisable = entry.disabled
                setOnAction { model.choose(side, entry.pick) }
            }
        }
    }

    /** Stops following the editor font. */
    fun dispose() {
        editor.node.styleProperty().unbind()
    }
}
