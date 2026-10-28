package dev.sluide.todos.view

import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoScope
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.stage.Window

/** What the folder dialog returns. */
internal data class FolderDraft(
    val scope: TodoScope,
    val name: String,
)

/** What the folder dialog needs: [folder] is null when adding. */
internal class FolderDialogRequest(
    val owner: Window?,
    val folder: TodoFolder?,
    val scope: TodoScope,
    val projectPossible: Boolean,
    /** What the user entered before a failed save: shown again instead of the [folder]. */
    val draft: FolderDraft? = null,
) {
    val initialScope: TodoScope get() = draft?.scope ?: scope
    val initialName: String get() = draft?.name ?: folder?.name.orEmpty()
}

/** The dialog to add or edit a folder: scope and name. */
internal class FolderDialog(
    request: FolderDialogRequest,
) : Dialog<FolderDraft>() {
    private val adding = request.folder == null
    private val confirm = ButtonType(if (adding) "Add" else "Save", ButtonBar.ButtonData.OK_DONE)

    private val selector = ScopeSelector(request.initialScope, request.projectPossible)
    val scopeButtons get() = selector.buttons
    val nameField = TextField(request.initialName).apply { promptText = "Name" }

    val confirmButton: Button get() = dialogPane.lookupButton(confirm) as Button

    init {
        request.owner?.let(::initOwner)
        title = if (adding) "New Folder" else "Edit Folder"
        headerText = null
        dialogPane.buttonTypes.setAll(confirm, ButtonType.CANCEL)
        dialogPane.content =
            GridPane().apply {
                hgap = 8.0
                vgap = 8.0
                padding = Insets(4.0)
                add(Label("Scope"), 0, 0)
                add(selector.row, 1, 0)
                add(Label("Name"), 0, 1)
                add(nameField, 1, 1)
                GridPane.setHgrow(nameField, Priority.ALWAYS)
            }
        dialogPane.prefWidth = WIDTH
        dialogPane.stylesheets +=
            checkNotNull(FolderDialog::class.java.getResource("/dev/sluide/todos/todos.css")).toExternalForm()
        nameField.textProperty().subscribe { _ -> confirmButton.isDisable = nameField.text.isBlank() }
        setResultConverter { button ->
            val saved = button == confirm
            if (saved) FolderDraft(selector.scope, nameField.text.trim()) else null
        }
        // Later, since the enabled default button takes the focus once the dialog is shown.
        setOnShown { Platform.runLater { nameField.requestFocus() } }
    }

    private companion object {
        const val WIDTH = 360.0
    }
}
