package dev.sluide.databases.ui.tree

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.ObjectKind
import dev.sluide.databases.sql.SqlNames
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** The identity of a tree node across reloads: its path of kind, schema, table, name and ref (overloads). */
internal data class NodeKey(
    val parent: NodeKey?,
    /** `null` for the Loading and Error rows. */
    val kind: ObjectKind?,
    val schema: String?,
    val table: String?,
    val name: String,
    val ref: String,
)

/** What a row of the tree shows. */
internal sealed interface TreeRow

internal data class ObjectRow(
    val obj: DbObject,
) : TreeRow

internal data object LoadingRow : TreeRow

internal data class ErrorRow(
    val message: String,
) : TreeRow

/** How far loading a list got. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Loaded<T>(
        val value: T,
    ) : Load<T>

    data class Failed(
        val message: String,
    ) : Load<Nothing>
}

/** What the context menu can do with a node. */
internal enum class NodeAction { OPEN_DATA, SHOW_DDL, COPY_NAME, COPY_QUALIFIED_NAME, GENERATE_SELECT }

/** The decisions of the object tree without the toolkit: keys, labels, applicable actions, generated SQL. */
internal object ObjectTreeModel {
    private val TABLE_LIKE = setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW)

    /** The rows below the hidden root: one per schema, each with its loaded objects or a Loading or Error row. */
    fun nodes(
        schemas: Load<List<String>>,
        schemaLoad: (String) -> Load<List<DbObject>>,
    ): List<SyncNode<NodeKey, TreeRow>> =
        when (schemas) {
            is Load.Loading -> {
                listOf(placeholder(null, LoadingRow))
            }

            is Load.Failed -> {
                listOf(placeholder(null, ErrorRow(schemas.message)))
            }

            is Load.Loaded -> {
                schemas.value.map { name ->
                    val obj = DbObject(ObjectKind.SCHEMA, name, name)
                    val key = schemaKey(name)
                    val children =
                        when (val load = schemaLoad(name)) {
                            is Load.Loading -> listOf(placeholder(key, LoadingRow))
                            is Load.Failed -> listOf(placeholder(key, ErrorRow(load.message)))
                            is Load.Loaded -> objectNodes(key, load.value)
                        }
                    SyncNode(key, ObjectRow(obj), children)
                }
            }
        }

    fun schemaKey(name: String) = NodeKey(null, ObjectKind.SCHEMA, name, null, name, "")

    private fun objectNodes(
        parent: NodeKey,
        objects: List<DbObject>,
    ): List<SyncNode<NodeKey, TreeRow>> =
        objects.map { obj ->
            val key = NodeKey(parent, obj.kind, obj.schema, obj.table, obj.name, obj.ref)
            // A schema opens with its tables visible.
            val expanded = obj.kind == ObjectKind.GROUP && parent.kind == ObjectKind.SCHEMA && obj.name == "tables"
            SyncNode(key, ObjectRow(obj), objectNodes(key, obj.children), expanded)
        }

    private fun placeholder(
        parent: NodeKey?,
        row: TreeRow,
    ) = SyncNode(NodeKey(parent, null, null, null, if (row is ErrorRow) "error" else "loading", ""), row)

    /** The text of a row; group labels are capitalised ("Tables"). */
    fun label(row: TreeRow): String =
        when (row) {
            is LoadingRow -> {
                "Loading…"
            }

            is ErrorRow -> {
                row.message
            }

            is ObjectRow -> {
                if (row.obj.kind ==
                    ObjectKind.GROUP
                ) {
                    row.obj.name.replaceFirstChar(Char::titlecase)
                } else {
                    row.obj.name
                }
            }
        }

    /** The muted text after the label. */
    fun detail(row: TreeRow): String = (row as? ObjectRow)?.obj?.detail.orEmpty()

    /** Whether Enter or a double click opens the data of [obj] instead of expanding it. */
    fun opensData(obj: DbObject) = obj.kind in TABLE_LIKE

    /** The context-menu actions that apply to [kind], in menu order (Refresh is always last and not listed). */
    fun actions(kind: ObjectKind): List<NodeAction> =
        buildList {
            if (kind in TABLE_LIKE) add(NodeAction.OPEN_DATA)
            if (kind != ObjectKind.SCHEMA && kind != ObjectKind.GROUP) add(NodeAction.SHOW_DDL)
            if (kind != ObjectKind.GROUP) {
                add(NodeAction.COPY_NAME)
                add(NodeAction.COPY_QUALIFIED_NAME)
            }
            if (kind in TABLE_LIKE) add(NodeAction.GENERATE_SELECT)
        }

    /** The name as written in SQL: schemas, tables and routines qualified, columns as `table.column` qualified. */
    fun qualifiedName(
        kind: DatabaseKind,
        obj: DbObject,
    ): String =
        when {
            obj.kind == ObjectKind.SCHEMA -> {
                SqlNames.quote(kind, obj.name)
            }

            obj.kind == ObjectKind.COLUMN && obj.table != null -> {
                SqlNames.qualified(kind, obj.schema, obj.table) + "." + SqlNames.quote(kind, obj.name)
            }

            // Keys, indexes, checks and triggers belong to a table and are named without a qualifier.
            obj.table != null -> {
                SqlNames.quote(kind, obj.name)
            }

            else -> {
                SqlNames.qualified(kind, obj.schema, obj.name)
            }
        }

    /** `SELECT cols FROM table;`, the columns one per line when there are more than three. */
    fun generateSelect(
        kind: DatabaseKind,
        table: DbObject,
    ): String {
        val columns = table.children.filter { it.kind == ObjectKind.COLUMN }.map { SqlNames.quote(kind, it.name) }
        val from = SqlNames.qualified(kind, table.schema, table.name)
        return when {
            columns.isEmpty() -> "SELECT * FROM $from;"
            columns.size <= 3 -> "SELECT ${columns.joinToString(", ")} FROM $from;"
            else -> "SELECT\n${columns.joinToString(",\n") { "    $it" }}\nFROM $from;"
        }
    }

    /** One line for an error row: the first line of the message without PostgreSQL's `ERROR: ` prefix. */
    fun errorMessage(error: Throwable): String {
        var t = error
        while ((t is CompletionException || t is ExecutionException) && t.cause != null) t = checkNotNull(t.cause)
        val line =
            t.message
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
        return line?.removePrefix("ERROR: ") ?: t.javaClass.simpleName
    }
}
