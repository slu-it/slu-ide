package dev.sluide.todos

import dev.sluide.todos.view.FolderDeletion
import dev.sluide.todos.view.FolderDialog
import dev.sluide.todos.view.FolderDialogRequest
import dev.sluide.todos.view.FolderDraft
import dev.sluide.todos.view.PlainDescriptionEditor
import dev.sluide.todos.view.TodoActions
import dev.sluide.todos.view.TodoDialog
import dev.sluide.todos.view.TodoDialogRequest
import dev.sluide.todos.view.TodoDraft
import dev.sluide.todos.view.TodoList
import dev.sluide.todos.view.askToDelete
import dev.sluide.todos.view.askToDeleteFolder
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

    /** Shows the dialog to add or edit a folder; tests replace it, since it blocks. */
    internal var editFolderDialog: (FolderDialogRequest) -> FolderDraft? =
        { FolderDialog(it).showAndWait().orElse(null) }

    /** Asks whether to delete a TODO; tests replace it, since it blocks. */
    internal var confirmDelete: (Todo) -> Boolean = { askToDelete(scene?.window, it) }

    /** Asks how to delete a folder (null = cancel); tests replace it, since it blocks. */
    internal var confirmDeleteFolder: (TodoFolder) -> FolderDeletion? = { askToDeleteFolder(scene?.window, it) }

    /** The window's project folder; null without one. */
    internal var folder: Path? = null
        private set

    private val actions = TodoActions(this, todos)

    internal val lists: Map<TodoScope, TodoList> =
        TodoScope.entries.associateWith { scope ->
            TodoList(scope, actions, { actions.add(scope) }, { actions.addFolder(scope) })
        }

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

    internal fun refresh(scope: TodoScope) {
        val items = todos.items(scope, folder)
        lists.getValue(scope).show(items)
        val count = items.sumOf { if (it is TodoFolder) it.todos.size else 1 }
        tabs.getValue(scope).text = "${if (scope == TodoScope.SHARED) "Shared" else "Project"} ($count)"
    }

    /** Shows the tab of [scope]. */
    internal fun select(scope: TodoScope) = tabPane.selectionModel.select(tabs.getValue(scope))
}
