package dev.sluide.databases.meta

/** The row sets of one MariaDB database (see [MariaQueries]). */
internal class MariaSchemaRows(
    val tables: List<Row> = emptyList(),
    val columns: List<Row> = emptyList(),
    val constraints: List<Row> = emptyList(),
    val keyColumns: List<Row> = emptyList(),
    val referential: List<Row> = emptyList(),
    val checks: List<Row> = emptyList(),
    val statistics: List<Row> = emptyList(),
    val triggers: List<Row> = emptyList(),
    val routines: List<Row> = emptyList(),
    val parameters: List<Row> = emptyList(),
    val events: List<Row> = emptyList(),
)

internal fun mariaColumnDetail(row: Row): String =
    buildList {
        add(row.text("type"))
        if (row.text("nullable").equals("NO", ignoreCase = true)) add("not null")
        // MariaDB reports a missing default of a nullable column as the text NULL.
        row.str("dflt")?.takeIf { it != "NULL" }?.let { add("default $it") }
        row.text("extra").takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
    }.joinToString(" ")

private val quietRules = setOf("RESTRICT", "NO ACTION")

private fun mariaKeys(
    schema: String,
    rows: MariaSchemaRows,
): List<DbObject> {
    val columns = rows.keyColumns.groupBy { it.text("tbl") to it.text("name") }
    val rules = rows.referential.associateBy { it.text("tbl") to it.text("name") }
    return rows.constraints.map { c ->
        val key = c.text("tbl") to c.text("name")
        val parts = columns[key].orEmpty().sortedBy { it.int("ord") }
        val cols = parts.joinToString(", ") { it.text("col") }
        when (c.text("type")) {
            "PRIMARY KEY" -> {
                DbObject(ObjectKind.PRIMARY_KEY, schema, key.second, key.first, "($cols)")
            }

            "FOREIGN KEY" -> {
                val first = parts.firstOrNull()
                val target = "${first?.text(
                    "rschema",
                )}.${first?.text("rtable")}(${parts.joinToString(", ") { it.text("rcol") }})"
                val onDelete = rules[key]?.text("ondelete").orEmpty()
                val rule =
                    if (onDelete.isEmpty() ||
                        onDelete in quietRules
                    ) {
                        ""
                    } else {
                        " on delete ${onDelete.lowercase()}"
                    }
                DbObject(ObjectKind.FOREIGN_KEY, schema, key.second, key.first, "($cols) → $target$rule")
            }

            else -> {
                DbObject(ObjectKind.UNIQUE, schema, key.second, key.first, "($cols)")
            }
        }
    }
}

private fun mariaIndexes(
    schema: String,
    rows: List<Row>,
): List<DbObject> =
    rows.groupBy { it.text("tbl") to it.text("name") }.map { (key, parts) ->
        val cols = parts.sortedBy { it.int("seq") }.joinToString(", ") { it.str("col") ?: "(expression)" }
        val unique = parts.first().int("nonunique") == 0
        DbObject(ObjectKind.INDEX, schema, key.second, key.first, "($cols)" + if (unique) " unique" else "")
    }

private fun mariaRoutines(
    schema: String,
    rows: MariaSchemaRows,
): List<DbObject> {
    val params = rows.parameters.groupBy { it.text("routine") to it.text("rtype") }
    return rows.routines.map {
        val name = it.text("name")
        val types =
            params[name to it.text("kind")]
                .orEmpty()
                .sortedBy { p ->
                    p.int("ord")
                }.joinToString(", ") { p -> p.text("type") }
        val isProcedure = it.text("kind") == "PROCEDURE"
        DbObject(
            if (isProcedure) ObjectKind.PROCEDURE else ObjectKind.FUNCTION,
            schema,
            name,
            detail = routineDetail(types, if (isProcedure) null else it.str("result")),
        )
    }
}

/** The group nodes of a MariaDB database from its `information_schema` rows. */
internal fun mariaSchemaTree(
    schema: String,
    rows: MariaSchemaRows,
): List<DbObject> {
    val relations =
        rows.tables.map {
            val kind =
                when (it.text("kind")) {
                    "BASE TABLE", "SYSTEM VERSIONED" -> ObjectKind.TABLE
                    "SEQUENCE" -> ObjectKind.SEQUENCE
                    else -> ObjectKind.VIEW
                }
            DbObject(kind, schema, it.text("name"))
        }
    val columns =
        rows.columns.map { DbObject(ObjectKind.COLUMN, schema, it.text("name"), it.text("tbl"), mariaColumnDetail(it)) }
    val checks =
        rows.checks.map { DbObject(ObjectKind.CHECK, schema, it.text("name"), it.text("tbl"), it.text("clause")) }
    val triggers =
        rows.triggers.map {
            val detail = "${it.text("timing")} ${it.text("event")}".lowercase()
            DbObject(ObjectKind.TRIGGER, schema, it.text("name"), it.text("tbl"), detail)
        }
    val events =
        rows.events.map {
            DbObject(ObjectKind.EVENT, schema, it.text("name"), detail = it.text("status").lowercase())
        }
    return buildSchemaTree(
        schema,
        SchemaLeaves(
            relations = relations,
            columns = columns,
            tableObjects = mariaKeys(schema, rows) + mariaIndexes(schema, rows.statistics) + checks + triggers,
            routines = mariaRoutines(schema, rows),
            events = events,
        ),
    )
}
