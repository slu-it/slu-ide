package dev.sluide.todos

import dev.sluide.projectdata.ProjectDataStore
import javafx.util.Subscription
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The field of the shared data file and of a project's data that holds the TODOs, in display order. */
internal const val TODOS_FIELD = "todos"

/**
 * The TODOs and folders of both scopes, in display order, kept in field `todos` of the shared data file and of each
 * project's data (ADR 0090, ADR 0101). A folder is an entry of that array with a string `folder` (its name) and its own
 * `todos`; folders do not nest. Application-wide: listeners refresh every window. Call on the FX thread (writes are
 * small and synchronous, as for ArgumentHistories).
 *
 * Fields of an entry that are not known stay as they are on every write, as do entries that are not listed (no object,
 * or no string `id`; inside a folder also nested folders).
 */
@Component
class Todos(
    private val store: ProjectDataStore,
) {
    private val listeners = CopyOnWriteArrayList<(TodoScope, Path?) -> Unit>()

    /** The tree of [scope] (of the project in [folder] for PROJECT; empty without a folder), in display order. */
    fun items(
        scope: TodoScope,
        folder: Path?,
    ): List<TodoItem> = parseItems(rawEntries(scope, folder))

    /** All TODOs of [scope] in display order, those in folders in place of their folder. */
    fun list(
        scope: TodoScope,
        folder: Path?,
    ): List<Todo> = items(scope, folder).flatMap { if (it is TodoFolder) it.todos else listOf(it as Todo) }

    /**
     * Adds a new TODO at the top of the folder [into], or of the root of [scope] if there is none or it does not exist;
     * the new TODO, or null if it could not be saved.
     */
    fun add(
        scope: TodoScope,
        folder: Path?,
        summary: String,
        description: String,
        into: String? = null,
    ): Todo? {
        val todo = Todo(UUID.randomUUID().toString(), summary, description)
        val saved =
            writeChecked(scope, folder) { root ->
                val array = destination(root, into) ?: return@writeChecked false
                array.insert(0, entry(todo.id, summary, description, null))
                true
            }
        return if (saved) todo else null
    }

    /**
     * Saves [summary] and [description] of the TODO [id] that was in [from], in [to], in the folder [targetFolder] (null =
     * root): in place if the scopes are equal and it is in that container already, else at the top of the target
     * (written first; the root if the folder is missing there) and then removed from where it was. A TODO no longer
     * there (deleted in another window) is added at the top of the target with its id. `true` if saved. If the target is
     * saved but the TODO cannot be removed from [from], it stays in both scopes and the result is `false`: a duplicate
     * is better than a loss (ADR 0090).
     */
    fun edit(
        id: String,
        from: TodoScope,
        to: TodoScope,
        folder: Path?,
        summary: String,
        description: String,
        targetFolder: String?,
    ): Boolean {
        if (from == to) {
            return writeChecked(to, folder) { root ->
                val found = findTodo(root, id)
                if (found != null && found.folderId == targetFolder) {
                    (found.node as ObjectNode).put("summary", summary).put("description", description)
                    return@writeChecked true
                }
                val array = destination(root, targetFolder) ?: return@writeChecked false
                val carried = found?.node
                removeTodo(root, id)
                array.insert(0, entry(id, summary, description, carried))
                true
            }
        }
        val carried = rawArray(from, folder)?.let { findTodo(it, id) }?.node
        val added =
            writeChecked(to, folder) { root ->
                val array = destination(root, targetFolder) ?: return@writeChecked false
                removeTodo(root, id)
                array.insert(0, entry(id, summary, description, carried))
                true
            }
        if (!added) return false
        return write(from, folder) { removeTodo(it, id) }
    }

    /**
     * Moves the TODO or folder [id] to [place]; `true` if saved, also if nothing was there to move. A folder only goes to
     * the root: for another [TodoPlace.folderId] the result is `false` and nothing is written. A missing target folder
     * leaves everything unchanged and the result is `true`.
     */
    fun move(
        scope: TodoScope,
        folder: Path?,
        id: String,
        place: TodoPlace,
    ): Boolean =
        writeChecked(scope, folder) { root ->
            val todo = findTodo(root, id)
            val folderNode = if (todo == null) findFolder(root, id) else null
            if (folderNode != null && place.folderId != null) return@writeChecked false
            if (todo == null && folderNode == null) return@writeChecked true
            val target =
                if (place.folderId == null) {
                    root
                } else {
                    val node = findFolder(root, place.folderId) ?: return@writeChecked true
                    contentsOf(node, true) ?: return@writeChecked false
                }
            val moved = if (todo != null) todo.array.remove(todo.index) else root.remove(root.indexOf(folderNode))
            insertAtListed(target, place.index, moved, if (place.folderId == null) ::isListed else ::isTodo)
            true
        }

    /** Deletes the TODO [id], at the root or in a folder; `true` if saved (also if it was not there). */
    fun delete(
        scope: TodoScope,
        folder: Path?,
        id: String,
    ): Boolean = write(scope, folder) { removeTodo(it, id) }

    /** Adds a new empty folder at the top of the root of [scope]; the folder, or null if it could not be saved. */
    fun addFolder(
        scope: TodoScope,
        folder: Path?,
        name: String,
    ): TodoFolder? {
        val created = TodoFolder(UUID.randomUUID().toString(), name, false, emptyList())
        val saved = write(scope, folder) { it.insert(0, newFolder(created.id, name)) }
        return if (saved) created else null
    }

    /**
     * Renames the folder [id] that was in [from], in [to]: in place if the scopes are equal, else with all its contents
     * and unknown fields at the root top of [to] (written first) and then removed from [from]. A folder no longer there
     * is created empty at the root top of [to]. `true` if saved; as for [edit], a failed removal leaves it in both.
     */
    fun editFolder(
        id: String,
        from: TodoScope,
        to: TodoScope,
        folder: Path?,
        name: String,
    ): Boolean {
        if (from == to) {
            return write(to, folder) { root ->
                val node = findFolder(root, id)
                if (node != null) node.put("folder", name) else root.insert(0, newFolder(id, name))
            }
        }
        val carried = rawArray(from, folder)?.let { findFolder(it, id) }
        val added =
            write(to, folder) { root ->
                removeFolder(root, id)
                carried?.get(FOLDER_TODOS_FIELD)?.filter(::isTodo)?.forEach {
                    it.idOrNull()?.let { todoId ->
                        removeTodo(root, todoId)
                    }
                }
                val copy = carried?.deepCopy() ?: newFolder(id, name)
                copy.put("folder", name)
                root.insert(0, copy)
            }
        if (!added) return false
        return write(from, folder) { removeFolder(it, id) }
    }

    /** Folds or unfolds the folder [id]; `true` if saved, also if it is not there (nothing changes then). */
    fun setCollapsed(
        scope: TodoScope,
        folder: Path?,
        id: String,
        collapsed: Boolean,
    ): Boolean = write(scope, folder) { findFolder(it, id)?.put("collapsed", collapsed) }

    /**
     * Expands or collapses the card of the TODO [id] (at the root or in a folder): `"expanded": true` while expanded,
     * the field is removed when collapsed. `true` if saved, also if it is not there (nothing changes then).
     */
    fun setExpanded(
        scope: TodoScope,
        folder: Path?,
        id: String,
        expanded: Boolean,
    ): Boolean =
        write(scope, folder) {
            val node = findTodo(it, id)?.node as? ObjectNode ?: return@write
            if (expanded) node.put("expanded", true) else node.remove("expanded")
        }

    /**
     * Deletes the folder [id]. With [keepTodos] all entries of its `todos` (listed or not, in order) take its place at the
     * root, else they go with it. `true` if saved (also if it was not there); `false` and unchanged if [keepTodos] but
     * its `todos` is no array.
     */
    fun deleteFolder(
        scope: TodoScope,
        folder: Path?,
        id: String,
        keepTodos: Boolean,
    ): Boolean =
        writeChecked(scope, folder) { root ->
            val matches = (0 until root.size()).filter { isFolder(root.get(it)) && root.get(it).idOrNull() == id }
            val contents = matches.map { if (keepTodos) contentsOf(root.get(it) as ObjectNode, false) else null }
            if (keepTodos && contents.any { it == null }) return@writeChecked false
            // From the back, so that the positions found stay valid.
            matches.indices.reversed().forEach { splice(root, matches[it], contents[it]?.toList().orEmpty()) }
            true
        }

    /**
     * Calls [listener] after every saved change with the scope and, for PROJECT, the project folder
     * (`toAbsolutePath().normalize()`; `null` for SHARED); on the caller's thread. Unsubscribing stops it.
     */
    fun onChanged(listener: (TodoScope, Path?) -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    private fun rawArray(
        scope: TodoScope,
        folder: Path?,
    ): ArrayNode? {
        val root =
            when (scope) {
                TodoScope.SHARED -> store.readShared()
                TodoScope.PROJECT -> folder?.let(store::read)
            }
        return root?.get(TODOS_FIELD) as? ArrayNode
    }

    private fun rawEntries(
        scope: TodoScope,
        folder: Path?,
    ): List<JsonNode> = rawArray(scope, folder)?.toList().orEmpty()

    /** The array a TODO is put on top of: that of the folder [id], or the [root] if there is none or it is missing. */
    private fun destination(
        root: ArrayNode,
        id: String?,
    ): ArrayNode? {
        val node = id?.let { findFolder(root, it) } ?: return root
        return contentsOf(node, true)
    }

    private fun write(
        scope: TodoScope,
        folder: Path?,
        change: (ArrayNode) -> Unit,
    ): Boolean =
        writeChecked(scope, folder) {
            change(it)
            true
        }

    /**
     * Applies [change] to the `todos` array of [scope] in one store write and notifies on success. If [change] returns
     * `false` it must not have changed anything: nothing is written and the result is `false`.
     */
    private fun writeChecked(
        scope: TodoScope,
        folder: Path?,
        change: (ArrayNode) -> Boolean,
    ): Boolean {
        var refused = false
        val edit: (ObjectNode) -> Unit = { root ->
            val existing = root.get(TODOS_FIELD)
            if (existing != null && !existing.isNull && existing !is ArrayNode) {
                // Not ours: left alone, so that nothing the user or a newer version stored there is lost.
                refused = true
            } else {
                val array = existing as? ArrayNode ?: root.arrayNode()
                if (change(array)) {
                    if (array.size() == 0) root.remove(TODOS_FIELD) else root.set(TODOS_FIELD, array)
                } else {
                    refused = true
                }
            }
        }
        val saved =
            when (scope) {
                TodoScope.SHARED -> store.updateShared(edit)
                TodoScope.PROJECT -> folder != null && store.update(folder, edit)
            }
        if (refused) return false
        if (saved) {
            val notified = if (scope == TodoScope.PROJECT) folder?.toAbsolutePath()?.normalize() else null
            listeners.forEach { it(scope, notified) }
        }
        return saved
    }

    private fun entry(
        id: String,
        summary: String,
        description: String,
        carried: JsonNode?,
    ): ObjectNode {
        val node = (carried as? ObjectNode)?.deepCopy() ?: ObjectNode(JsonNodeFactory.instance)
        node.put("id", id)
        node.put("summary", summary)
        node.put("description", description)
        return node
    }
}
