package dev.sluide.todos.view

import dev.sluide.todos.DescriptionEditor
import dev.sluide.todos.Todo
import dev.sluide.todos.TodoScope
import dev.sluide.todos.markdown.MarkdownContext
import dev.sluide.todos.markdown.MarkdownRenderer
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
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
) {
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

    private val scopeGroup = ToggleGroup()
    val scopeButtons: Map<TodoScope, ToggleButton> =
        TodoScope.entries.associateWith { scope ->
            ToggleButton(if (scope == TodoScope.SHARED) "Shared" else "Project").apply {
                toggleGroup = scopeGroup
                userData = scope
                isDisable = scope == TodoScope.PROJECT && !request.projectPossible
            }
        }

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
    val scope: TodoScope get() = scopeGroup.selectedToggle.userData as TodoScope

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
        scopeGroup.selectToggle(scopeButtons.getValue(request.initialScope))
        // A choice is always selected: clicking the selected button keeps it.
        scopeGroup.selectedToggleProperty().subscribe { old, new -> if (new == null) scopeGroup.selectToggle(old) }
        modeGroup.selectedToggleProperty().subscribe { old, new ->
            if (new == null) modeGroup.selectToggle(old) else showMode(new === previewButton)
        }
        modeGroup.selectToggle(writeButton)
        summaryField.textProperty().subscribe { _ -> confirmButton.isDisable = summaryField.text.isBlank() }
        setResultConverter { button ->
            if (button == confirm) TodoDraft(scope, summaryField.text.trimEnd(), editor.text.trimEnd()) else null
        }
        setOnShown {
            // Later, since the enabled default button takes the focus once the dialog is shown.
            Platform.runLater { if (adding) summaryField.requestFocus() else editor.focus() }
        }
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
        val scopeRow =
            HBox(scopeButtons.values.first(), scopeButtons.values.last()).apply {
                styleClass +=
                    "todo-segmented"
            }
        scopeButtons.values.forEach { it.styleClass += "todo-segment" }
        val modeRow = HBox(writeButton, previewButton).apply { styleClass += "todo-segmented" }
        listOf(writeButton, previewButton).forEach { it.styleClass += "todo-segment" }
        descriptionHolder.minHeight = 0.0
        return GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            padding = Insets(4.0)
            add(Label("Scope"), 0, 0)
            add(scopeRow, 1, 0)
            add(Label("Summary"), 0, 1)
            add(summaryField, 1, 1)
            add(Label("Description"), 0, 2)
            add(modeRow, 1, 2)
            add(descriptionHolder, 0, 3, 2, 1)
            GridPane.setHgrow(summaryField, Priority.ALWAYS)
            GridPane.setHgrow(descriptionHolder, Priority.ALWAYS)
            GridPane.setVgrow(descriptionHolder, Priority.ALWAYS)
        }
    }

    private companion object {
        const val WIDTH = 560.0
        const val HEIGHT = 480.0
    }
}
