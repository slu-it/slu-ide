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

/** Where a TODO is kept: in the shared data file, for every project, or in the project's own data. */
enum class TodoScope { SHARED, PROJECT }

/** A TODO: a one-line [summary] and a Markdown [description] (may be empty). [id] is a random UUID string. */
data class Todo(
    val id: String,
    val summary: String,
    val description: String,
)

/**
 * The TODOs of both scopes, in display order, kept in field `todos` of the shared data file and of each project's data
 * (ADR 0090, ADR 0101). Application-wide: listeners refresh every window. Call on the FX thread (writes are small and
 * synchronous, as for ArgumentHistories).
 *
 * Fields of an entry that are not known stay as they are on every write, as do entries that are not listed (no object,
 * or no string `id`).
 */
@Component
class Todos(
    private val store: ProjectDataStore,
) {
    private val listeners = CopyOnWriteArrayList<(TodoScope, Path?) -> Unit>()

    /** The TODOs of [scope] (of the project in [folder] for PROJECT; empty without a folder). */
    fun list(
        scope: TodoScope,
        folder: Path?,
    ): List<Todo> =
        rawEntries(scope, folder)
            .filter(::isListed)
            .map { Todo(it.get("id").stringValue(), it.text("summary"), it.text("description")) }

    /** Adds a new TODO at the top of [scope]; the new TODO, or null if it could not be saved. */
    fun add(
        scope: TodoScope,
        folder: Path?,
        summary: String,
        description: String,
    ): Todo? {
        val todo = Todo(UUID.randomUUID().toString(), summary, description)
        val saved = write(scope, folder) { it.insert(0, entry(todo.id, summary, description, null)) }
        return if (saved) todo else null
    }

    /**
     * Saves [summary] and [description] of the TODO [id] that was in [from], in [to]: in place if the scopes are equal,
     * else at the top of [to] (written first) and then removed from [from]. A TODO no longer there (deleted in another
     * window) is added at the top of [to] with its id. `true` if saved. If the target is saved but the TODO cannot be
     * removed from [from], it stays in both scopes and the result is `false`: a duplicate is better than a loss (ADR 0090).
     */
    fun edit(
        id: String,
        from: TodoScope,
        to: TodoScope,
        folder: Path?,
        summary: String,
        description: String,
    ): Boolean {
        if (from == to) {
            return write(to, folder) { array ->
                val node = array.firstOrNull { isListed(it) && it.get("id").stringValue() == id } as? ObjectNode
                if (node != null) {
                    node.put("summary", summary)
                    node.put("description", description)
                } else {
                    array.insert(0, entry(id, summary, description, null))
                }
            }
        }
        val carried = rawEntries(from, folder).firstOrNull { isListed(it) && it.get("id").stringValue() == id }
        val added =
            write(to, folder) { array ->
                removeId(array, id)
                array.insert(0, entry(id, summary, description, carried))
            }
        if (!added) return false
        return write(from, folder) { removeId(it, id) }
    }

    /** Moves the TODO [id] to position [toIndex] (0-based, among the listed TODOs, coerced into range); `true` if saved. */
    fun move(
        scope: TodoScope,
        folder: Path?,
        id: String,
        toIndex: Int,
    ): Boolean =
        write(scope, folder) { array ->
            val at = array.indexOfFirst { isListed(it) && it.get("id").stringValue() == id }
            if (at < 0) return@write
            val node = array.remove(at)
            val listed = (0 until array.size()).filter { isListed(array.get(it)) }
            val position =
                if (toIndex >= listed.size) {
                    listed.lastOrNull()?.plus(1) ?: array.size()
                } else {
                    listed[toIndex.coerceAtLeast(0)]
                }
            array.insert(position, node)
        }

    /** Deletes the TODO [id]; `true` if saved (also if it was not there). */
    fun delete(
        scope: TodoScope,
        folder: Path?,
        id: String,
    ): Boolean = write(scope, folder) { removeId(it, id) }

    /**
     * Calls [listener] after every saved change with the scope and, for PROJECT, the project folder
     * (`toAbsolutePath().normalize()`; `null` for SHARED); on the caller's thread. Unsubscribing stops it.
     */
    fun onChanged(listener: (TodoScope, Path?) -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    private fun rawEntries(
        scope: TodoScope,
        folder: Path?,
    ): List<JsonNode> {
        val root =
            when (scope) {
                TodoScope.SHARED -> store.readShared()
                TodoScope.PROJECT -> folder?.let(store::read)
            }
        return (root?.get(TODOS_FIELD) as? ArrayNode)?.toList().orEmpty()
    }

    /** Applies [change] to the `todos` array of [scope] in one store write and notifies on success. */
    private fun write(
        scope: TodoScope,
        folder: Path?,
        change: (ArrayNode) -> Unit,
    ): Boolean {
        var foreign = false
        val edit: (ObjectNode) -> Unit = { root ->
            val existing = root.get(TODOS_FIELD)
            if (existing != null && !existing.isNull && existing !is ArrayNode) {
                // Not ours: left alone, so that nothing the user or a newer version stored there is lost.
                foreign = true
            } else {
                val array = existing as? ArrayNode ?: root.arrayNode()
                change(array)
                if (array.size() == 0) root.remove(TODOS_FIELD) else root.set(TODOS_FIELD, array)
            }
        }
        val saved =
            when (scope) {
                TodoScope.SHARED -> store.updateShared(edit)
                TodoScope.PROJECT -> folder != null && store.update(folder, edit)
            }
        if (foreign) return false
        if (saved) {
            val notified = if (scope == TodoScope.PROJECT) folder?.toAbsolutePath()?.normalize() else null
            listeners.forEach { it(scope, notified) }
        }
        return saved
    }

    private fun isListed(node: JsonNode): Boolean = node is ObjectNode && node.get("id")?.isString == true

    private fun removeId(
        array: ArrayNode,
        id: String,
    ) {
        for (i in array.size() - 1 downTo 0) {
            val node = array.get(i)
            if (isListed(node) && node.get("id").stringValue() == id) array.remove(i)
        }
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

    private fun JsonNode.text(field: String): String = get(field)?.takeIf { it.isString }?.stringValue().orEmpty()
}
