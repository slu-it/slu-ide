package dev.sluide.databases.meta

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.sql.SqlNames
import java.sql.Connection

/** A `SHOW CREATE` statement and the result column that holds the DDL. */
internal data class ShowCreate(
    val sql: String,
    val column: String,
)

/** Which `SHOW CREATE` gives the DDL of [obj]; columns, keys, indexes and checks show their table's. */
internal fun mariaShowCreate(obj: DbObject): ShowCreate {
    fun name(
        schema: String?,
        name: String,
    ) = SqlNames.qualified(DatabaseKind.MARIADB, schema, name)

    fun show(
        what: String,
        target: String,
        column: String,
    ) = ShowCreate("SHOW CREATE $what $target", column)
    return when (obj.kind) {
        ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW -> show("VIEW", name(obj.schema, obj.name), "Create View")
        ObjectKind.FUNCTION -> show("FUNCTION", name(obj.schema, obj.name), "Create Function")
        ObjectKind.PROCEDURE -> show("PROCEDURE", name(obj.schema, obj.name), "Create Procedure")
        ObjectKind.TRIGGER -> show("TRIGGER", name(obj.schema, obj.name), "SQL Original Statement")
        ObjectKind.EVENT -> show("EVENT", name(obj.schema, obj.name), "Create Event")
        ObjectKind.SEQUENCE -> show("SEQUENCE", name(obj.schema, obj.name), "Create Table")
        else -> show("TABLE", name(obj.schema, obj.table ?: obj.name), "Create Table")
    }
}

/** Introspection of a MariaDB database (a "schema" here) through `information_schema` (ADR 0121). */
internal object MariaDbIntrospector : Introspector {
    override fun schemas(
        connection: Connection,
        includeSystem: Boolean,
    ): List<String> =
        filterSchemas(
            DatabaseKind.MARIADB,
            connection.rows(MariaQueries.SCHEMAS).map { it.text("name") },
            includeSystem,
        )

    override fun schema(
        connection: Connection,
        schema: String,
    ): List<DbObject> =
        mariaSchemaTree(
            schema,
            MariaSchemaRows(
                tables = connection.rows(MariaQueries.TABLES, schema),
                columns = connection.rows(MariaQueries.COLUMNS, schema),
                constraints = connection.rows(MariaQueries.CONSTRAINTS, schema),
                keyColumns = connection.rows(MariaQueries.KEY_COLUMNS, schema),
                referential = connection.rows(MariaQueries.REFERENTIAL, schema),
                checks = connection.rows(MariaQueries.CHECKS, schema),
                statistics = connection.rows(MariaQueries.STATISTICS, schema),
                triggers = connection.rows(MariaQueries.TRIGGERS, schema),
                routines = connection.rows(MariaQueries.ROUTINES, schema),
                parameters = connection.rows(MariaQueries.PARAMETERS, schema),
                events = connection.rows(MariaQueries.EVENTS, schema),
            ),
        )

    override fun ddl(
        connection: Connection,
        obj: DbObject,
    ): String {
        val show = mariaShowCreate(obj)
        // The statement text is built from quoted names; SHOW takes no bind parameters.
        return connection.createStatement().use { st ->
            st.executeQuery(show.sql).use { rs ->
                val labels = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                // A column node of a view gets SHOW CREATE TABLE, which answers "Create View".
                val label = labels.firstOrNull { it == show.column } ?: labels.firstOrNull { it.startsWith("Create ") }
                if (label != null && rs.next()) rs.getString(label)?.let { withSemicolon(it) }.orEmpty() else ""
            }
        }
    }

    override fun primaryKey(
        connection: Connection,
        schema: String?,
        table: String,
    ): List<String> = connection.rows(MariaQueries.PRIMARY_KEY, schema, table).map { it.text("name") }
}
