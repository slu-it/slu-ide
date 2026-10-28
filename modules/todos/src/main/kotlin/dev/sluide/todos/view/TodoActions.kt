package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoPanel
import dev.sluide.todos.TodoPlace
import dev.sluide.todos.TodoScope
import dev.sluide.todos.Todos
import dev.sluide.todos.markdown.MarkdownContext

private const val SAVE_FAILED =
    "Could not save the TODO.\nThe shared data file or the project data could not be written; see the log."

private const val SAVE_FOLDER_FAILED =
    "Could not save the folder.\nThe shared data file or the project data could not be written; see the log."

/** What the cards and folders of [panel] do: the dialogs, and the calls into [todos] with their failure handling. */
internal class TodoActions(
    private val panel: TodoPanel,
    private val todos: Todos,
) : TodoHost {
    private val project get() = panel.folder

    override fun edit(
        todo: Todo,
        scope: TodoScope,
        folderId: String?,
    ) {
        val draft =
            ask<TodoDraft>({ panel.editTodo(request(todo, scope, it, folderId)) }, SAVE_FAILED) {
                todos.edit(todo.id, scope, it.scope, project, it.summary, it.description, it.folderId)
            } ?: return
        if (draft.scope != scope) panel.select(draft.scope)
    }

    override fun delete(
        todo: Todo,
        scope: TodoScope,
    ) {
        if (panel.confirmDelete(todo) && !todos.delete(scope, project, todo.id)) failed(scope)
    }

    override fun saveDescription(
        todo: Todo,
        scope: TodoScope,
        folderId: String?,
        description: String,
    ) {
        if (!todos.edit(todo.id, scope, scope, project, todo.summary, description, folderId)) {
            failed(scope)
            // The checkbox and the renderer's source go back to what is stored.
            panel.lists
                .getValue(scope)
                .allCards
                .firstOrNull { it.todo.id == todo.id }
                ?.reset()
        }
    }

    override fun move(
        scope: TodoScope,
        id: String,
        place: TodoPlace,
    ) {
        if (!todos.move(scope, project, id, place)) failed(scope)
    }

    override fun addInto(
        scope: TodoScope,
        folderId: String,
    ) = add(scope, folderId)

    override fun editFolder(
        folder: TodoFolder,
        scope: TodoScope,
    ) {
        val draft =
            ask<FolderDraft>({ panel.editFolderDialog(folderRequest(folder, scope, it)) }, SAVE_FOLDER_FAILED) {
                todos.editFolder(folder.id, scope, it.scope, project, it.name)
            } ?: return
        if (draft.scope != scope) panel.select(draft.scope)
    }

    override fun deleteFolder(
        folder: TodoFolder,
        scope: TodoScope,
    ) {
        val choice = panel.confirmDeleteFolder(folder) ?: return
        if (!todos.deleteFolder(scope, project, folder.id, choice == FolderDeletion.KEEP_TODOS)) {
            failed(scope, SAVE_FOLDER_FAILED)
        }
    }

    override fun setExpanded(
        todo: Todo,
        scope: TodoScope,
        expanded: Boolean,
    ) {
        if (!todos.setExpanded(scope, project, todo.id, expanded)) failed(scope)
    }

    override fun setCollapsed(
        folder: TodoFolder,
        scope: TodoScope,
        collapsed: Boolean,
    ) {
        if (!todos.setCollapsed(scope, project, folder.id, collapsed)) failed(scope, SAVE_FOLDER_FAILED)
    }

    override fun markdown(
        scope: TodoScope,
        sourceChanged: (String) -> Unit,
    ) = MarkdownContext(
        if (scope == TodoScope.PROJECT) project else null,
        { panel.openWebPage(it) },
        { panel.openFile(it) },
        sourceChanged,
    )

    /** Asks for a new TODO in [scope] (in the folder [into] if given). */
    fun add(
        scope: TodoScope,
        into: String? = null,
    ) {
        val draft =
            ask<TodoDraft>({ panel.editTodo(request(null, scope, it, into)) }, SAVE_FAILED) {
                todos.add(it.scope, project, it.summary, it.description, it.folderId) != null
            } ?: return
        // A TODO added to a collapsed folder would be hidden.
        draft.folderId?.let { expand(draft.scope, it) }
        if (draft.scope != scope) panel.select(draft.scope)
    }

    private fun expand(
        scope: TodoScope,
        folderId: String,
    ) {
        val folder = todos.items(scope, project).filterIsInstance<TodoFolder>().firstOrNull { it.id == folderId }
        if (folder?.collapsed == true) todos.setCollapsed(scope, project, folderId, false)
    }

    /** Asks for a new folder in [scope]. */
    fun addFolder(scope: TodoScope) {
        val draft =
            ask<FolderDraft>({ panel.editFolderDialog(folderRequest(null, scope, it)) }, SAVE_FOLDER_FAILED) {
                todos.addFolder(it.scope, project, it.name) != null
            } ?: return
        if (draft.scope != scope) panel.select(draft.scope)
    }

    /** Tells that a change could not be saved, and shows what is stored. */
    private fun failed(
        scope: TodoScope,
        message: String = SAVE_FAILED,
    ) {
        panel.showError(message)
        panel.refresh(scope)
    }

    private fun request(
        todo: Todo?,
        scope: TodoScope,
        draft: TodoDraft?,
        initialFolderId: String?,
    ) = TodoDialogRequest(
        panel.scene?.window,
        todo,
        scope,
        project != null,
        project,
        panel.descriptionEditor,
        { panel.openWebPage(it) },
        { panel.openFile(it) },
        draft,
        { todos.items(it, project).filterIsInstance<TodoFolder>() },
        initialFolderId,
    )

    private fun folderRequest(
        folder: TodoFolder?,
        scope: TodoScope,
        draft: FolderDraft?,
    ) = FolderDialogRequest(panel.scene?.window, folder, scope, project != null, draft)

    /** Asks for a draft until it is saved or the dialog is cancelled; a failed save shows the draft again. */
    private fun <D : Any> ask(
        show: (D?) -> D?,
        failure: String,
        save: (D) -> Boolean,
    ): D? {
        var draft: D? = null
        while (true) {
            draft = show(draft) ?: return null
            if (save(draft)) return draft
            panel.showError(failure)
        }
    }
}
