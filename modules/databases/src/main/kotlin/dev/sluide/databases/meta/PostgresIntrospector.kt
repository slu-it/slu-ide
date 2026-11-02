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

    private fun loadRows(
        connection: Connection,
        schema: String,
    ) = PgSchemaRows(
        relations = connection.rows(PgQueries.RELATIONS, schema),
        columns = connection.rows(PgQueries.COLUMNS, schema),
        constraints = connection.rows(PgQueries.CONSTRAINTS, schema),
        indexes = connection.rows(PgQueries.INDEXES, schema),
        triggers = connection.rows(PgQueries.TRIGGERS, schema),
        routines = connection.rows(PgQueries.ROUTINES, schema),
        types = connection.rows(PgQueries.TYPES, schema),
    )

    override fun schema(
        connection: Connection,
        schema: String,
    ): List<DbObject> = pgSchemaTree(schema, loadRows(connection, schema))

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
                pgTableDdl(schema, name, loadRows(connection, schema))
            }

            ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW -> {
                pgViewDdl(obj, one(PgQueries.VIEW_DEF, schema, name)?.text("def").orEmpty())
            }

            ObjectKind.FUNCTION, ObjectKind.PROCEDURE -> {
                one(PgQueries.FUNCTION_DEF, schema, name, obj.ref)?.str("def")?.let { withSemicolon(it) }.orEmpty()
            }

            ObjectKind.SEQUENCE -> {
                one(PgQueries.SEQUENCE_DEF, schema, name)?.let { pgSequenceDdl(obj, it) }.orEmpty()
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
                pgTableChildDdl(obj, loadRows(connection, schema)).orEmpty()
            }
        }
    }

    override fun primaryKey(
        connection: Connection,
        schema: String?,
        table: String,
    ): List<String> = connection.rows(PgQueries.PRIMARY_KEY, schema, table).map { it.text("name") }
}
