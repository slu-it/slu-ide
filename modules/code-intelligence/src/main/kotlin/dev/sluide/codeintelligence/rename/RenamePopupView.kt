package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.ui.CaretNote
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window

/**
 * The [RenameView] of an editor: notes at the caret ([CaretNote]), the [RenameField] at the name, the preview dialog
 * ([confirmRename]) and an `Alert` for failures, both owned by the editor's window. FX thread.
 */
internal class RenamePopupView(
    private val editor: ExtensibleCodeEditor,
) : RenameView {
    private val notes = CaretNote(editor)
    private val field = RenameField(editor)

    private val window: Window? get() = editor.popupOwner?.scene?.window

    override fun note(
        offset: Int,
        text: String,
    ) = notes.show(offset, text)

    override fun askName(
        range: IntRange,
        name: String,
        check: (String) -> String?,
        onName: (String) -> Unit,
    ) {
        notes.hide()
        if (!field.show(range.first, range.last + 1, name, check, onName)) {
            notes.show(range.first, "The name is not on screen")
        }
    }

    override fun confirm(preview: RenamePreview): Boolean = confirmRename(window, preview)

    override fun tell(message: String) {
        Alert(Alert.AlertType.WARNING, message, ButtonType.OK)
            .apply {
                title = "Rename"
                headerText = null
                window?.let(::initOwner)
            }.showAndWait()
    }

    override fun close() {
        notes.close()
        field.close()
    }
}

/**
 * Shows the rename [preview] in a dialog owned by [owner] (title "Rename", the question as header, the summary and
 * the files with their counts and notes); `true` if the user chose Rename. With a [RenamePreview.refusal] it says why
 * and offers Cancel only, as applying would be refused.
 */
internal fun confirmRename(
    owner: Window?,
    preview: RenamePreview,
): Boolean {
    val rename = ButtonType("Rename", ButtonData.OK_DONE)
    val files =
        ListView<String>().apply {
            items.setAll(preview.rows.map(::rowText))
            prefHeight = (preview.rows.size.coerceIn(1, 12) * 24 + 4).toDouble()
            prefWidth = 520.0
        }
    val content =
        VBox(8.0, Label(preview.summary), files).apply {
            preview.refusal?.let { children += Label(it).apply { isWrapText = true } }
            minHeight = Region.USE_PREF_SIZE
        }
    // Applying would be refused: Cancel only.
    val buttons = if (preview.refusal == null) listOf(rename, ButtonType.CANCEL) else listOf(ButtonType.CANCEL)
    val dialog =
        Dialog<ButtonType>().apply {
            title = "Rename"
            headerText = preview.header
            owner?.let(::initOwner)
            dialogPane.content = content
            dialogPane.minHeight = Region.USE_PREF_SIZE
            dialogPane.buttonTypes.setAll(buttons)
        }
    return dialog.showAndWait().orElse(null) == rename
}

/** One file of the preview list: "path · 2 changes", with its note if any. */
internal fun rowText(row: PreviewRow): String =
    listOfNotNull(row.path, counted(row.count, "change"), row.note).joinToString(" · ")
