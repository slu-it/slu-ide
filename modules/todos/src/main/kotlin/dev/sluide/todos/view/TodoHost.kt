package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoPlace
import dev.sluide.todos.TodoScope
import dev.sluide.todos.markdown.MarkdownContext
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon

/** What the TODO cards and folders need from the panel; [folderId] is the folder that holds the TODO, null = root. */
internal interface TodoHost {
    /** The ids of the TODOs shown expanded; kept for the window's lifetime. */
    val expandedIds: MutableSet<String>

    fun edit(
        todo: Todo,
        scope: TodoScope,
        folderId: String?,
    )

    fun delete(
        todo: Todo,
        scope: TodoScope,
    )

    /** Saves a description changed in the card (a toggled task). */
    fun saveDescription(
        todo: Todo,
        scope: TodoScope,
        folderId: String?,
        description: String,
    )

    fun move(
        scope: TodoScope,
        id: String,
        place: TodoPlace,
    )

    /** Adds a TODO, preselecting the folder [folderId]. */
    fun addInto(
        scope: TodoScope,
        folderId: String,
    )

    fun editFolder(
        folder: TodoFolder,
        scope: TodoScope,
    )

    fun deleteFolder(
        folder: TodoFolder,
        scope: TodoScope,
    )

    fun setCollapsed(
        folder: TodoFolder,
        scope: TodoScope,
        collapsed: Boolean,
    )

    /** The context of the Markdown of a TODO in [scope]. */
    fun markdown(
        scope: TodoScope,
        sourceChanged: (String) -> Unit,
    ): MarkdownContext
}

/** A flat button with only [icon]. */
internal fun iconButton(
    icon: Ikon,
    tip: String,
    id: String,
): Button =
    Button(null, FontIcon(icon)).apply {
        this.id = id
        styleClass += "todo-icon-button"
        tooltip = Tooltip(tip)
    }
