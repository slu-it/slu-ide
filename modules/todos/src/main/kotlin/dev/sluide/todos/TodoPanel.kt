package dev.sluide.todos

import dev.sluide.todos.markdown.MarkdownContext
import dev.sluide.todos.view.PlainDescriptionEditor
import dev.sluide.todos.view.TodoDialog
import dev.sluide.todos.view.TodoDialogRequest
import dev.sluide.todos.view.TodoDraft
import dev.sluide.todos.view.TodoHost
import dev.sluide.todos.view.TodoList
import dev.sluide.todos.view.askToDelete
import dev.sluide.todos.view.iconButton
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.input.KeyCode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path

private const val SAVE_FAILED =
    "Could not save the TODO.\nThe shared data file or the project data could not be written; see the log."

private const val STRIPE_WIDTH = 26.0
private const val EXPANDED_WIDTH = 320.0

/** The TODO panel at the right of a window: a stripe while collapsed, else the Shared and Project tabs of TODO cards. */
@Component
@Scope("window")
class TodoPanel(
    private val todos: Todos,
) : BorderPane(),
    AutoCloseable {
    /** Whether the panel shows its tabs; false at the start of every window. */
    val expanded: BooleanProperty = SimpleBooleanProperty(false)

    var openWebPage: (URI) -> Unit = {}
    var openFile: (Path) -> Unit = {}

    /** Makes the description field of the dialog; default: a plain wrapped TextArea. */
    var descriptionEditor: (String) -> DescriptionEditor = { PlainDescriptionEditor(it) }

    /** Called when the panel collapses while the focus is in it, to hand the focus back (the window: the editor). */
    var returnFocus: () -> Unit = {}

    /** Shows the dialog to add or edit a TODO; tests replace it, since it blocks. */
    internal var editTodo: (TodoDialogRequest) -> TodoDraft? = { TodoDialog(it).showAndWait().orElse(null) }

    /** Shows an error message and waits until it is closed; tests replace it, since it blocks. */
    internal var showError: (String) -> Unit = { message ->
        Alert(Alert.AlertType.ERROR, message)
            .apply {
                scene?.window?.let(::initOwner)
                headerText = null
            }.showAndWait()
    }

    /** Asks whether to delete a TODO; tests replace it, since it blocks. */
    internal var confirmDelete: (Todo) -> Boolean = { askToDelete(scene?.window, it) }

    private var folder: Path? = null
    private val expandedIds = mutableSetOf<String>()

    private val host =
        object : TodoHost {
            override val expandedIds get() = this@TodoPanel.expandedIds

            override fun edit(
                todo: Todo,
                scope: TodoScope,
            ) = editExisting(todo, scope)

            override fun delete(
                todo: Todo,
                scope: TodoScope,
            ) {
                if (confirmDelete(todo) && !todos.delete(scope, folder, todo.id)) failed(scope)
            }

            override fun saveDescription(
                todo: Todo,
                scope: TodoScope,
                description: String,
            ) {
                if (!todos.edit(
                        todo.id,
                        scope,
                        scope,
                        folder,
                        todo.summary,
                        description,
                        todos.containerOf(scope, folder, todo.id),
                    )
                ) {
                    failed(scope)
                    // The checkbox and the renderer's source go back to what is stored.
                    lists
                        .getValue(scope)
                        .cards
                        .firstOrNull { it.todo.id == todo.id }
                        ?.reset()
                }
            }

            override fun move(
                scope: TodoScope,
                id: String,
                toIndex: Int,
            ) {
                if (!todos.move(scope, folder, id, TodoPlace(null, toIndex))) failed(scope)
            }

            override fun markdown(
                scope: TodoScope,
                sourceChanged: (String) -> Unit,
            ) = MarkdownContext(
                if (scope == TodoScope.PROJECT) folder else null,
                { openWebPage(it) },
                { openFile(it) },
                sourceChanged,
            )
        }

    internal val lists: Map<TodoScope, TodoList> =
        TodoScope.entries.associateWith { scope -> TodoList(scope, host) { addTodo(scope) } }

    internal val tabs: Map<TodoScope, Tab> =
        TodoScope.entries.associateWith { scope ->
            Tab("", lists.getValue(scope)).apply { isClosable = false }
        }

    internal val tabPane = TabPane(tabs.getValue(TodoScope.SHARED), tabs.getValue(TodoScope.PROJECT))

    /** The collapsed panel; a click anywhere on it expands. */
    internal val stripe: Node =
        VBox(
            FontIcon(MaterialDesignC.CHEVRON_LEFT),
            Group(Label("TODOs").apply { rotate = -90.0 }),
        ).apply {
            styleClass += "todo-stripe"
            isFocusTraversable = true
            setOnMouseClicked { expanded.set(true) }
            setOnKeyPressed {
                if (it.code == KeyCode.ENTER || it.code == KeyCode.SPACE) {
                    expanded.set(true)
                    it.consume()
                }
            }
        }

    internal val collapseButton = iconButton(MaterialDesignC.CHEVRON_RIGHT, "Collapse", "todo-collapse")

    private val content: Region =
        BorderPane().apply {
            styleClass += "todo-content"
            top =
                HBox(collapseButton, Label("TODOs").apply { styleClass += "todo-title" }).apply {
                    styleClass += "todo-header"
                }
            center = tabPane
        }

    private val subscription: Subscription

    init {
        styleClass += "todo-panel"
        stylesheets += checkNotNull(TodoPanel::class.java.getResource("/dev/sluide/todos/todos.css")).toExternalForm()
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        collapseButton.setOnAction { expanded.set(false) }
        HBox.setHgrow(content, Priority.ALWAYS)
        expanded.subscribe { old, new -> if (old != new) show(new) }
        show(false)
        subscription = todos.onChanged(::changed)
        refresh(TodoScope.SHARED)
        refresh(TodoScope.PROJECT)
    }

    /** The window's project folder; null disables the Project tab (and the Project scope in the dialog). */
    fun open(folder: Path?) {
        this.folder = folder
        tabs.getValue(TodoScope.PROJECT).isDisable = folder == null
        if (folder == null) tabPane.selectionModel.select(tabs.getValue(TodoScope.SHARED))
        refresh(TodoScope.SHARED)
        refresh(TodoScope.PROJECT)
    }

    /** Focuses the content of the expanded panel (the selected tab's Add button). */
    fun focusContent() {
        if (!expanded.value) return
        (tabPane.selectionModel.selectedItem?.content as? TodoList)?.addButton?.requestFocus()
    }

    /** Stops following the TODO store; the window calls it when it closes (Todos is application-wide). */
    fun dispose() {
        subscription.unsubscribe()
        lists.values.forEach(TodoList::cancelDrag)
    }

    /** The same as [dispose], so that the window scope's destruction stops the panel. */
    override fun close() = dispose()

    private fun show(expanding: Boolean) {
        if (!expanding && focusIsInside()) returnFocus()
        center = if (expanding) content else stripe
        prefWidth = if (expanding) EXPANDED_WIDTH else STRIPE_WIDTH
        if (expanding) {
            // The new content must be laid out before it can take the focus.
            applyCss()
            layout()
            focusContent()
        }
    }

    private fun focusIsInside(): Boolean {
        var node = scene?.focusOwner
        while (node != null) {
            if (node === this) return true
            node = node.parent
        }
        return false
    }

    private fun changed(
        scope: TodoScope,
        path: Path?,
    ) {
        if (scope == TodoScope.SHARED || path == folder?.toAbsolutePath()?.normalize()) refresh(scope)
    }

    private fun refresh(scope: TodoScope) {
        val items = todos.list(scope, folder)
        lists.getValue(scope).show(items)
        tabs.getValue(scope).text = "${if (scope == TodoScope.SHARED) "Shared" else "Project"} (${items.size})"
    }

    /** Tells that a change could not be saved, and shows what is stored. */
    private fun failed(scope: TodoScope) {
        showError(SAVE_FAILED)
        refresh(scope)
    }

    private fun request(
        todo: Todo?,
        scope: TodoScope,
        draft: TodoDraft?,
    ) = TodoDialogRequest(
        scene?.window,
        todo,
        scope,
        folder != null,
        folder,
        descriptionEditor,
        { openWebPage(it) },
        { openFile(it) },
        draft,
    )

    /** Asks for a draft until it is saved or the dialog is cancelled; a failed save shows the draft again. */
    private fun ask(
        todo: Todo?,
        scope: TodoScope,
        save: (TodoDraft) -> Boolean,
    ): TodoDraft? {
        var draft: TodoDraft? = null
        while (true) {
            draft = editTodo(request(todo, scope, draft)) ?: return null
            if (save(draft)) return draft
            showError(SAVE_FAILED)
        }
    }

    private fun addTodo(scope: TodoScope) {
        val draft = ask(null, scope) { todos.add(it.scope, folder, it.summary, it.description) != null } ?: return
        if (draft.scope != scope) tabPane.selectionModel.select(tabs.getValue(draft.scope))
    }

    private fun editExisting(
        todo: Todo,
        scope: TodoScope,
    ) {
        val draft =
            ask(todo, scope) {
                todos.edit(
                    todo.id,
                    scope,
                    it.scope,
                    folder,
                    it.summary,
                    it.description,
                    todos.containerOf(scope, folder, todo.id),
                )
            } ?: return
        if (draft.scope != scope) tabPane.selectionModel.select(tabs.getValue(draft.scope))
    }
}
