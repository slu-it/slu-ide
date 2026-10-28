package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoScope
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignP

/** The content of a tab: a toolbar with the Add button over the scrolling column of the cards of [scope]. */
internal class TodoList(
    val scope: TodoScope,
    private val host: TodoHost,
    onAdd: () -> Unit,
) : BorderPane() {
    val addButton =
        Button("Add", FontIcon(MaterialDesignP.PLUS)).apply {
            id = "todo-add"
            styleClass += "todo-add-button"
            tooltip = Tooltip("Add TODO")
            setOnAction { onAdd() }
        }

    val cardsBox = VBox().apply { styleClass += "todo-cards" }

    val scroll =
        ScrollPane(cardsBox).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            styleClass += "todo-scroll"
        }

    private val drag = CardDrag(scroll, cardsBox) { id, index -> host.move(scope, id, index) }

    /** The cards in display order. */
    val cards: List<TodoCard> get() = cardsBox.children.filterIsInstance<TodoCard>()

    init {
        styleClass += "todo-list"
        top = HBox(addButton).apply { styleClass += "todo-toolbar" }
        center = scroll
    }

    /** Ends a drag in progress; the panel calls it when it is disposed. */
    fun cancelDrag() = drag.cancel()

    /** Shows [todos]; a card of a TODO that is still there stays (with its expansion and the scroll position). */
    fun show(todos: List<Todo>) {
        val existing = cards.associateBy { it.todo.id }
        val shown =
            todos.map { todo ->
                existing[todo.id]?.also { it.update(todo) }
                    ?: TodoCard(todo, scope, host).also(drag::attach)
            }
        cardsBox.children.setAll(shown.ifEmpty { listOf(Label("No TODOs").apply { styleClass += "todo-empty" }) })
    }
}
