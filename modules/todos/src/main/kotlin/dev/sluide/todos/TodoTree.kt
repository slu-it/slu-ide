package dev.sluide.todos

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** The field of a folder that holds its TODOs. */
internal const val FOLDER_TODOS_FIELD = "todos"

internal fun JsonNode.idOrNull(): String? = (this as? ObjectNode)?.get("id")?.takeIf { it.isString }?.stringValue()

internal fun JsonNode.text(field: String): String = get(field)?.takeIf { it.isString }?.stringValue().orEmpty()

/** A folder: an object with a string `id` and a string `folder` (its name). */
internal fun isFolder(node: JsonNode): Boolean = node.idOrNull() != null && node.get("folder")?.isString == true

/** A TODO: an object with a string `id` that is not a folder. */
internal fun isTodo(node: JsonNode): Boolean = node.idOrNull() != null && node.get("folder")?.isString != true

/** Listed at the root of a tab: a TODO or a folder. */
internal fun isListed(node: JsonNode): Boolean = node.idOrNull() != null

internal fun todoOf(node: JsonNode) = Todo(node.idOrNull().orEmpty(), node.text("summary"), node.text("description"))

/** The TODOs of [folder], or null if its `todos` is present but no array. A missing one is [create]d or empty. */
internal fun contentsOf(
    folder: ObjectNode,
    create: Boolean,
): ArrayNode? {
    val existing = folder.get(FOLDER_TODOS_FIELD)
    return when {
        existing is ArrayNode -> {
            existing
        }

        existing == null || existing.isNull -> {
            if (create) folder.putArray(FOLDER_TODOS_FIELD) else ArrayNode(JsonNodeFactory.instance)
        }

        else -> {
            null
        }
    }
}

internal fun parseItems(root: List<JsonNode>): List<TodoItem> =
    root.mapNotNull { node ->
        when {
            isFolder(node) -> {
                TodoFolder(
                    node.idOrNull().orEmpty(),
                    node.text("folder"),
                    node.get("collapsed")?.let { it.isBoolean && it.booleanValue() } ?: false,
                    (node.get(FOLDER_TODOS_FIELD) as? ArrayNode)?.filter(::isTodo)?.map(::todoOf).orEmpty(),
                )
            }

            isTodo(node) -> {
                todoOf(node)
            }

            else -> {
                null
            }
        }
    }

internal fun findFolder(
    root: ArrayNode,
    id: String,
): ObjectNode? = root.firstOrNull { isFolder(it) && it.idOrNull() == id } as? ObjectNode

/** A TODO found by id: its [node] in [array], which is the contents of [folderId] (null = the root). */
internal class Located(
    val array: ArrayNode,
    val index: Int,
    val folderId: String?,
) {
    val node: JsonNode get() = array.get(index)
}

internal fun findTodo(
    root: ArrayNode,
    id: String,
): Located? {
    root.indexOfFirst { isTodo(it) && it.idOrNull() == id }.takeIf { it >= 0 }?.let { return Located(root, it, null) }
    for (folder in root.filter(::isFolder)) {
        val array = folder.get(FOLDER_TODOS_FIELD) as? ArrayNode ?: continue
        val at = array.indexOfFirst { isTodo(it) && it.idOrNull() == id }
        if (at >= 0) return Located(array, at, folder.idOrNull())
    }
    return null
}

/** Removes every TODO [id] at the root and in folders. */
internal fun removeTodo(
    root: ArrayNode,
    id: String,
) {
    fun strip(array: ArrayNode) {
        for (i in array.size() - 1 downTo 0) {
            if (isTodo(array.get(i)) && array.get(i).idOrNull() == id) array.remove(i)
        }
    }
    strip(root)
    root.filter(::isFolder).forEach { (it.get(FOLDER_TODOS_FIELD) as? ArrayNode)?.let(::strip) }
}

/** Removes every folder [id] at the root. */
internal fun removeFolder(
    root: ArrayNode,
    id: String,
) {
    for (i in root.size() - 1 downTo 0) {
        if (isFolder(root.get(i)) && root.get(i).idOrNull() == id) root.remove(i)
    }
}

/** Inserts [node] before the [index]-th listed entry of [array] (coerced into range), after the last one if beyond. */
internal fun insertAtListed(
    array: ArrayNode,
    index: Int,
    node: JsonNode,
    listed: (JsonNode) -> Boolean,
) {
    val positions = (0 until array.size()).filter { listed(array.get(it)) }
    val at = index.coerceAtLeast(0)
    val position =
        if (at >= positions.size) {
            positions.lastOrNull()?.plus(1) ?: array.size()
        } else {
            positions[at]
        }
    array.insert(position, node)
}

/** Replaces the folder at [at] of [root] by [entries] (all kept, listed or not, in order). */
internal fun splice(
    root: ArrayNode,
    at: Int,
    entries: List<JsonNode>,
) {
    root.remove(at)
    entries.forEachIndexed { i, node -> root.insert(at + i, node) }
}

internal fun newFolder(
    id: String,
    name: String,
): ObjectNode {
    val node = ObjectNode(JsonNodeFactory.instance)
    node.put("id", id)
    node.put("folder", name)
    node.put("collapsed", false)
    node.putArray(FOLDER_TODOS_FIELD)
    return node
}
