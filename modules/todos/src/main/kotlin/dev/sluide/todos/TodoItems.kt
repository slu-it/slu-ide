package dev.sluide.todos

/** Where a TODO is kept: in the shared data file, for every project, or in the project's own data. */
enum class TodoScope { SHARED, PROJECT }

/** An entry of a tab: a [Todo] or a [TodoFolder]. [id] is a random UUID string. */
sealed interface TodoItem {
    val id: String
}

/**
 * A TODO: a one-line [summary] and a Markdown [description] (may be empty); [expanded] if its card shows the
 * description.
 */
data class Todo(
    override val id: String,
    val summary: String,
    val description: String,
    val expanded: Boolean = false,
) : TodoItem

/** A folder of TODOs, one level deep; [todos] are its listed TODOs in display order. */
data class TodoFolder(
    override val id: String,
    val name: String,
    val collapsed: Boolean,
    val todos: List<Todo>,
) : TodoItem

/**
 * Where a TODO or folder lands: [folderId] null = the root of the tab; [index] among the listed items of that container
 * once the moved item is removed from its old place (coerced into range).
 */
data class TodoPlace(
    val folderId: String?,
    val index: Int,
)
