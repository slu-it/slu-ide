package dev.sluide.todos.view

import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoScope
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignD
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignP

/** A folder: its header, and below it, while the folder is expanded, its TODO cards or a hint if it is empty. */
internal class FolderView(
    folder: TodoFolder,
    val scope: TodoScope,
    private val host: TodoHost,
) : VBox() {
    var folder: TodoFolder = folder
        private set

    val toggle = iconButton(MaterialDesignC.CHEVRON_DOWN, "Collapse", "todo-folder-toggle")
    val name =
        Label().apply {
            styleClass += "todo-folder-name"
            isWrapText = true
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
            HBox.setHgrow(this, Priority.ALWAYS)
        }
    val add = iconButton(MaterialDesignP.PLUS, "Add TODO to folder", "todo-folder-add")
    val edit = iconButton(MaterialDesignP.PENCIL, "Edit folder", "todo-folder-edit")
    val delete = iconButton(MaterialDesignD.DELETE, "Delete folder", "todo-folder-delete")
    val header = HBox(toggle, name, add, edit, delete).apply { styleClass += "todo-folder-header" }

    /** The TODO cards, or the hint of an empty folder; not shown while the folder is collapsed. */
    val body = VBox().apply { styleClass += "todo-folder-cards" }

    /** The hint of an empty, expanded folder. */
    val emptyHint = Label("Drag TODOs here").apply { styleClass += "todo-folder-empty" }

    /** The cards in display order. */
    val cards: List<TodoCard> get() = body.children.filterIsInstance<TodoCard>()

    init {
        styleClass += "todo-folder"
        toggle.setOnAction { host.setCollapsed(this.folder, scope, !this.folder.collapsed) }
        add.setOnAction { host.addInto(scope, this.folder.id) }
        edit.setOnAction { host.editFolder(this.folder, scope) }
        delete.setOnAction { host.deleteFolder(this.folder, scope) }
        name.setOnMouseClicked {
            if (it.button == MouseButton.PRIMARY && it.clickCount == 2) host.editFolder(this.folder, scope)
        }
    }

    /** Shows [next], the same folder (by id) as it is now stored, with its [todoCards] (in its order). */
    fun show(
        next: TodoFolder,
        todoCards: List<TodoCard>,
    ) {
        folder = next
        name.text = "${next.name} (${next.todos.size})"
        name.graphic =
            FontIcon(if (next.collapsed) MaterialDesignF.FOLDER_OUTLINE else MaterialDesignF.FOLDER_OPEN_OUTLINE)
        toggle.graphic = FontIcon(if (next.collapsed) MaterialDesignC.CHEVRON_RIGHT else MaterialDesignC.CHEVRON_DOWN)
        toggle.tooltip = Tooltip(if (next.collapsed) "Expand" else "Collapse")
        body.children.setAll(todoCards.ifEmpty { listOf(emptyHint) })
        children.setAll(if (next.collapsed) listOf(header) else listOf(header, body))
    }
}
