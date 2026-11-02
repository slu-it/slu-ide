package dev.sluide.databases.meta

import dev.sluide.databases.connect.DatabaseKind
import java.sql.Connection

/** Introspection of the connected PostgreSQL database through `pg_catalog` (ADR 0121). */
internal object PostgresIntrospector : Introspector {
    override fun schemas(
        connection: Connection,
        includeSystem: Boolean,
    ): List<String> =
        filterSchemas(
            DatabaseKind.POSTGRESQL,
            connection.rows(PgQueries.SCHEMAS).map { it.text("name") },
            includeSystem,
        )

    /** The table-level rows of [schema]: all tables, or only [table] (no routines and types then). */
    private fun loadRows(
        connection: Connection,
        schema: String,
        table: String?,
        owned: Boolean = false,
    ) = PgSchemaRows(
        relations = connection.rows(PgQueries.RELATIONS, schema, table, table),
        columns = connection.rows(PgQueries.COLUMNS, schema, table, table),
        constraints = connection.rows(PgQueries.CONSTRAINTS, schema, table, table),
        indexes = connection.rows(PgQueries.INDEXES, schema, table, table),
        triggers = connection.rows(PgQueries.TRIGGERS, schema, table, table),
        ownedSequences =
            if (owned) connection.rows(PgQueries.OWNED_SEQUENCES, schema, table, table) else emptyList(),
    )

    override fun schema(
        connection: Connection,
        schema: String,
    ): List<DbObject> {
        val rows = loadRows(connection, schema, null)
        return pgSchemaTree(
            schema,
            PgSchemaRows(
                relations = rows.relations,
                columns = rows.columns,
                constraints = rows.constraints,
                indexes = rows.indexes,
                triggers = rows.triggers,
                routines = connection.rows(PgQueries.ROUTINES, schema),
                types = connection.rows(PgQueries.TYPES, schema),
            ),
        )
    }

    override fun ddl(
        connection: Connection,
        obj: DbObject,
    ): String {
        val schema = obj.schema.orEmpty()
        val name = obj.name

        fun one(
            sql: String,
            vararg params: Any?,
        ) = connection.rows(sql, *params).firstOrNull()
        return when (obj.kind) {
            ObjectKind.TABLE -> {
                pgTableDdl(schema, name, loadRows(connection, schema, name, owned = true))
            }

            ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW -> {
                val definition = one(PgQueries.VIEW_DEF, schema, name)?.text("def").orEmpty()
                val rows =
                    if (obj.kind == ObjectKind.MATERIALIZED_VIEW) {
                        PgSchemaRows(indexes = connection.rows(PgQueries.INDEXES, schema, name, name))
                    } else {
                        PgSchemaRows()
                    }
                pgViewDdl(obj, definition, rows)
            }

            ObjectKind.FUNCTION, ObjectKind.PROCEDURE -> {
                one(PgQueries.FUNCTION_DEF, schema, name, obj.ref)?.str("def")?.let { withSemicolon(it) }.orEmpty()
            }

            ObjectKind.SEQUENCE -> {
                one(PgQueries.SEQUENCE_DEF, schema, name)?.let { pgSequenceDdl(obj.schema, name, it) }.orEmpty()
            }

            ObjectKind.ENUM -> {
                pgEnumDdl(obj, connection.rows(PgQueries.ENUM_LABELS, schema, name).map { it.text("label") })
            }

            ObjectKind.DOMAIN -> {
                pgDomainDdl(
                    obj,
                    one(PgQueries.DOMAIN_DEF, schema, name),
                    connection.rows(PgQueries.DOMAIN_CONSTRAINTS, schema, name),
                )
            }

            ObjectKind.COMPOSITE_TYPE -> {
                pgCompositeDdl(obj, connection.rows(PgQueries.COMPOSITE_ATTRIBUTES, schema, name))
            }

            else -> {
                val table = obj.table ?: return ""
                pgTableChildDdl(obj, loadRows(connection, schema, table)).orEmpty()
            }
        }
    }

    override fun primaryKey(
        connection: Connection,
        schema: String?,
        table: String,
    ): List<String> = connection.rows(PgQueries.PRIMARY_KEY, schema, table).map { it.text("name") }
}
