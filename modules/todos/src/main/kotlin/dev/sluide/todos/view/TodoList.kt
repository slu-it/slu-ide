package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoItem
import dev.sluide.todos.TodoPlace
import dev.sluide.todos.TodoScope
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignP

/** The content of a tab: the Add and New Folder buttons over the scrolling column of the items of [scope]. */
internal class TodoList(
    val scope: TodoScope,
    private val host: TodoHost,
    onAdd: () -> Unit,
    onNewFolder: () -> Unit,
) : BorderPane() {
    val addButton =
        Button("Add", FontIcon(MaterialDesignP.PLUS)).apply {
            id = "todo-add"
            styleClass += "todo-add-button"
            tooltip = Tooltip("Add TODO")
            setOnAction { onAdd() }
        }

    val newFolderButton =
        Button("New Folder", FontIcon(MaterialDesignF.FOLDER_PLUS)).apply {
            id = "todo-new-folder"
            styleClass += "todo-add-button"
            tooltip = Tooltip("New folder")
            setOnAction { onNewFolder() }
        }

    val cardsBox = VBox().apply { styleClass += "todo-cards" }

    val scroll =
        ScrollPane(cardsBox).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            styleClass += "todo-scroll"
        }

    private val drag = CardDrag(scroll, cardsBox) { id, index -> host.move(scope, id, TodoPlace(null, index)) }

    /** The cards at the root in display order. */
    val cards: List<TodoCard> get() = cardsBox.children.filterIsInstance<TodoCard>()

    /** The folders in display order. */
    val folders: List<FolderView> get() = cardsBox.children.filterIsInstance<FolderView>()

    /** All cards, those in folders too. */
    val allCards: List<TodoCard> get() = cards + folders.flatMap(FolderView::cards)

    init {
        styleClass += "todo-list"
        top = HBox(addButton, newFolderButton).apply { styleClass += "todo-toolbar" }
        center = scroll
    }

    /** Ends a drag in progress; the panel calls it when it is disposed. */
    fun cancelDrag() = drag.cancel()

    /**
     * Shows [items]; the card of a TODO and the view of a folder that are still there stay (with the expansion and the
     * scroll position), also when a TODO moved to another container.
     */
    fun show(items: List<TodoItem>) {
        val cardsById = allCards.associateBy { it.todo.id }
        val foldersById = folders.associateBy { it.folder.id }

        fun card(
            todo: Todo,
            folderId: String?,
        ) = cardsById[todo.id]?.also { it.update(todo, folderId) }
            ?: TodoCard(todo, scope, host, folderId).also(drag::attach)

        val shown =
            items.map { item ->
                when (item) {
                    is Todo -> {
                        card(item, null)
                    }

                    is TodoFolder -> {
                        (foldersById[item.id] ?: FolderView(item, scope, host)).also { view ->
                            view.show(item, item.todos.map { card(it, item.id) })
                        }
                    }
                }
            }
        cardsBox.children.setAll(shown.ifEmpty { listOf(Label("No TODOs").apply { styleClass += "todo-empty" }) })
    }
}
