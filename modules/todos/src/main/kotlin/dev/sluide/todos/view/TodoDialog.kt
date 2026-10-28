package dev.sluide.todos.view

import dev.sluide.todos.DescriptionEditor
import dev.sluide.todos.Todo
import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoScope
import dev.sluide.todos.markdown.MarkdownContext
import dev.sluide.todos.markdown.MarkdownRenderer
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.stage.Window
import java.net.URI
import java.nio.file.Path

/** What the TODO dialog returns. */
internal data class TodoDraft(
    val scope: TodoScope,
    val summary: String,
    val description: String,
    /** The folder to keep the TODO in, null = none. */
    val folderId: String? = null,
)

/** What the TODO dialog needs: [todo] is null when adding. */
internal class TodoDialogRequest(
    val owner: Window?,
    val todo: Todo?,
    val scope: TodoScope,
    val projectPossible: Boolean,
    val folder: Path?,
    val descriptionEditor: (String) -> DescriptionEditor,
    val openWebPage: (URI) -> Unit,
    val openFile: (Path) -> Unit,
    /** What the user entered before a failed save: shown again instead of the [todo]. */
    val draft: TodoDraft? = null,
    /** The folders of a scope, in display order. */
    val folders: (TodoScope) -> List<TodoFolder> = { emptyList() },
    /** The folder preselected (the TODO's folder when editing, the folder whose + was used); null = no folder. */
    val initialFolderId: String? = null,
) {
    /** The folder shown selected: that of the [draft] after a failed save, else [initialFolderId]. */
    val selectedFolderId: String? get() = if (draft != null) draft.folderId else initialFolderId
    val initialScope: TodoScope get() = draft?.scope ?: scope
    val initialSummary: String get() = draft?.summary ?: todo?.summary.orEmpty()
    val initialDescription: String get() = draft?.description ?: todo?.description.orEmpty()
}

/** A plain, wrapped text area as the description field, until the window supplies a code editor. */
internal class PlainDescriptionEditor(
    initial: String,
) : DescriptionEditor {
    private val area = TextArea(initial).apply { isWrapText = true }
    override val node = area
    override val text: String get() = area.text.orEmpty()

    override fun focus() = area.requestFocus()
}

/** The dialog to add or edit a TODO: scope, summary and a description that can be previewed as Markdown. */
internal class TodoDialog(
    private val request: TodoDialogRequest,
) : Dialog<TodoDraft>() {
    private val adding = request.todo == null
    private val confirm = ButtonType(if (adding) "Add" else "Save", ButtonBar.ButtonData.OK_DONE)

    private val selector = ScopeSelector(request.initialScope, request.projectPossible)
    val scopeButtons: Map<TodoScope, ToggleButton> get() = selector.buttons

    /** One entry of the folder box: [id] null = no folder. */
    class FolderChoice(
        val id: String?,
        private val label: String,
    ) {
        override fun toString() = label
    }

    val folderBox = ComboBox<FolderChoice>().apply { maxWidth = Double.MAX_VALUE }

    val summaryField = TextField(request.initialSummary).apply { promptText = "Summary" }
    val editor: DescriptionEditor = request.descriptionEditor(request.initialDescription)

    private val modeGroup = ToggleGroup()
    val writeButton = ToggleButton("Write").apply { toggleGroup = modeGroup }
    val previewButton = ToggleButton("Preview").apply { toggleGroup = modeGroup }

    private val preview =
        ScrollPane().apply {
            isFitToWidth = true
            styleClass += "todo-preview"
        }
    val descriptionHolder = StackPane(editor.node)

    val confirmButton: Button get() = dialogPane.lookupButton(confirm) as Button

    /** The scope that is selected. */
    val scope: TodoScope get() = selector.scope

    init {
        request.owner?.let(::initOwner)
        title = if (adding) "Add TODO" else "Edit TODO"
        headerText = null
        isResizable = true
        dialogPane.buttonTypes.setAll(confirm, ButtonType.CANCEL)
        dialogPane.content = content()
        dialogPane.setPrefSize(WIDTH, HEIGHT)
        dialogPane.stylesheets +=
            checkNotNull(TodoDialog::class.java.getResource("/dev/sluide/todos/todos.css")).toExternalForm()
        fillFolders(request.selectedFolderId)
        // The folders of the selected scope; the folder stays selected if the new scope has one with its id.
        selector.onChange { fillFolders(folderBox.value?.id) }
        modeGroup.selectedToggleProperty().subscribe { old, new ->
            if (new == null) modeGroup.selectToggle(old) else showMode(new === previewButton)
        }
        modeGroup.selectToggle(writeButton)
        summaryField.textProperty().subscribe { _ -> confirmButton.isDisable = summaryField.text.isBlank() }
        setResultConverter { button ->
            val saved = button == confirm
            if (saved) {
                TodoDraft(
                    scope,
                    summaryField.text.trimEnd(),
                    editor.text.trimEnd(),
                    folderBox.value?.id,
                )
            } else {
                null
            }
        }
        setOnShown {
            // Later, since the enabled default button takes the focus once the dialog is shown.
            Platform.runLater { if (adding) summaryField.requestFocus() else editor.focus() }
        }
    }

    private fun fillFolders(selectedId: String?) {
        val choices =
            listOf(FolderChoice(null, "No folder")) + request.folders(scope).map { FolderChoice(it.id, it.name) }
        folderBox.items.setAll(choices)
        folderBox.value = choices.firstOrNull { it.id == selectedId } ?: choices.first()
    }

    /** Links and images of a shared TODO do not resolve in the project. */
    private fun previewFolder(): Path? = if (scope == TodoScope.PROJECT) request.folder else null

    private fun showMode(previewing: Boolean) {
        if (previewing) {
            val context = MarkdownContext(previewFolder(), request.openWebPage, request.openFile, null)
            preview.content = MarkdownRenderer(context).render(editor.text)
            descriptionHolder.children.setAll(preview)
        } else {
            descriptionHolder.children.setAll(editor.node)
        }
    }

    private fun content(): GridPane {
        val modeRow = HBox(writeButton, previewButton).apply { styleClass += "todo-segmented" }
        listOf(writeButton, previewButton).forEach { it.styleClass += "todo-segment" }
        descriptionHolder.minHeight = 0.0
        return GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            padding = Insets(4.0)
            add(Label("Scope"), 0, 0)
            add(selector.row, 1, 0)
            add(Label("Summary"), 0, 1)
            add(summaryField, 1, 1)
            add(Label("Folder"), 0, 2)
            add(folderBox, 1, 2)
            add(Label("Description"), 0, 3)
            add(modeRow, 1, 3)
            add(descriptionHolder, 0, 4, 2, 1)
            GridPane.setHgrow(summaryField, Priority.ALWAYS)
            GridPane.setHgrow(folderBox, Priority.ALWAYS)
            GridPane.setHgrow(descriptionHolder, Priority.ALWAYS)
            GridPane.setVgrow(descriptionHolder, Priority.ALWAYS)
        }
    }

    private companion object {
        const val WIDTH = 560.0
        const val HEIGHT = 480.0
    }
}
