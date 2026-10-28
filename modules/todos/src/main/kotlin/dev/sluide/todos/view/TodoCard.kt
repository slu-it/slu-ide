package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoScope
import dev.sluide.todos.markdown.MarkdownRenderer
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignD
import org.kordamp.ikonli.materialdesign2.MaterialDesignP

/** One TODO: its summary row, and below it the rendered description while the card is expanded. */
internal class TodoCard(
    todo: Todo,
    val scope: TodoScope,
    private val host: TodoHost,
    folderId: String?,
) : VBox() {
    /** The folder that holds the TODO, null = root; changes when the card is reused in another container. */
    var folderId: String? = folderId
        private set

    var todo: Todo = todo
        private set

    val toggle = iconButton(MaterialDesignC.CHEVRON_RIGHT, "Expand", "todo-toggle")
    val summary =
        Label().apply {
            styleClass += "todo-summary"
            isWrapText = true
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
            HBox.setHgrow(this, Priority.ALWAYS)
        }
    val edit = iconButton(MaterialDesignP.PENCIL, "Edit", "todo-edit")
    val delete = iconButton(MaterialDesignD.DELETE, "Delete", "todo-delete")

    /** The row that is dragged to reorder; its buttons are not part of the drag. */
    val header = HBox(toggle, summary, edit, delete)

    /** The rendered description, created when first expanded and again after the description changed. */
    var body: Region? = null
        private set

    val isExpanded: Boolean get() = todo.expanded && todo.description.isNotBlank()

    init {
        styleClass += "todo-card"
        header.styleClass += "todo-card-header"
        toggle.setOnAction {
            host.setExpanded(this.todo, scope, !this.todo.expanded)
        }
        edit.setOnAction { host.edit(this.todo, scope, this.folderId) }
        delete.setOnAction { host.delete(this.todo, scope) }
        summary.setOnMouseClicked {
            if (it.button == MouseButton.PRIMARY && it.clickCount == 2) host.edit(this.todo, scope, this.folderId)
        }
        refresh()
    }

    /** Shows [next], the same TODO (by id) as it is now stored, in [folder], expanded as stored. */
    fun update(
        next: Todo,
        folder: String?,
    ) {
        if (next.description != todo.description) body = null
        todo = next
        folderId = folder
        refresh()
    }

    /** Renders the description again from the stored TODO (after a toggle that could not be saved). */
    fun reset() {
        body = null
        refresh()
    }

    private fun refresh() {
        summary.text = todo.summary
        val hasBody = todo.description.isNotBlank()
        // Without a description the chevron keeps its space, so the summaries of all cards line up.
        toggle.isVisible = hasBody
        toggle.isDisable = !hasBody
        toggle.graphic = FontIcon(if (isExpanded) MaterialDesignC.CHEVRON_DOWN else MaterialDesignC.CHEVRON_RIGHT)
        toggle.tooltip = Tooltip(if (isExpanded) "Collapse" else "Expand")
        children.setAll(if (isExpanded) listOf(header, body()) else listOf(header))
    }

    private fun body(): Region =
        body ?: MarkdownRenderer(
            host.markdown(scope) { host.saveDescription(todo, scope, folderId, it) },
        ).render(todo.description).also {
            it.styleClass += "todo-card-body"
            body = it
        }
}
