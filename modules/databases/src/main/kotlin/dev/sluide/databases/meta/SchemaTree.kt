package dev.sluide.databases.meta

import dev.sluide.databases.connect.DatabaseKind

/** The flat catalog objects of one schema, already turned into nodes; [buildSchemaTree] arranges them. */
internal class SchemaLeaves(
    /** TABLE, VIEW, MATERIALIZED_VIEW and SEQUENCE nodes without children. */
    val relations: List<DbObject> = emptyList(),
    /** COLUMN nodes with `table` set, in ordinal order. */
    val columns: List<DbObject> = emptyList(),
    /** PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, INDEX and TRIGGER nodes with `table` set. */
    val tableObjects: List<DbObject> = emptyList(),
    val routines: List<DbObject> = emptyList(),
    val types: List<DbObject> = emptyList(),
    val events: List<DbObject> = emptyList(),
)

internal fun group(
    schema: String?,
    label: String,
    children: List<DbObject>,
): DbObject? = if (children.isEmpty()) null else DbObject(ObjectKind.GROUP, schema, label, children = children)

internal fun filterSchemas(
    kind: DatabaseKind,
    names: List<String>,
    includeSystem: Boolean,
): List<String> = names.filter { includeSystem || !kind.isSystemSchema(it) }.sorted()

/** The group nodes of [schema] in the documented order, dropping empty groups. */
internal fun buildSchemaTree(
    schema: String,
    leaves: SchemaLeaves,
): List<DbObject> {
    val columnsOf = leaves.columns.groupBy { it.table }
    val objectsOf = leaves.tableObjects.groupBy { it.table }

    fun relation(r: DbObject): DbObject {
        val cols = columnsOf[r.name].orEmpty()
        if (r.kind != ObjectKind.TABLE) return if (cols.isEmpty()) r else r.copy(children = cols)
        val own = objectsOf[r.name].orEmpty()

        fun of(vararg kinds: ObjectKind) = own.filter { it.kind in kinds }.sortedBy { it.name }
        val keys = of(ObjectKind.PRIMARY_KEY).plus(of(ObjectKind.UNIQUE))
        val subgroups =
            listOfNotNull(
                group(schema, "keys", keys),
                group(schema, "foreign keys", of(ObjectKind.FOREIGN_KEY)),
                group(schema, "indexes", of(ObjectKind.INDEX)),
                group(schema, "checks", of(ObjectKind.CHECK)),
                group(schema, "triggers", of(ObjectKind.TRIGGER)),
            )
        return r.copy(children = cols + subgroups)
    }

    fun relations(kind: ObjectKind) =
        leaves.relations
            .filter { it.kind == kind }
            .sortedBy { it.name }
            .map(::relation)
    return listOfNotNull(
        group(schema, "tables", relations(ObjectKind.TABLE)),
        group(schema, "views", relations(ObjectKind.VIEW)),
        group(schema, "materialized views", relations(ObjectKind.MATERIALIZED_VIEW)),
        group(schema, "routines", leaves.routines.sortedWith(compareBy({ it.name }, { it.ref }))),
        group(schema, "sequences", relations(ObjectKind.SEQUENCE)),
        group(schema, "types", leaves.types.sortedBy { it.name }),
        group(schema, "events", leaves.events.sortedBy { it.name }),
    )
}

/** `(a, b) → c` style detail of a routine; the result is left out when blank. */
internal fun routineDetail(
    params: String,
    result: String?,
): String = "($params)" + if (result.isNullOrBlank()) "" else " → $result"

internal fun withSemicolon(sql: String): String = sql.trim().trimEnd(';').trimEnd() + ";\n"
