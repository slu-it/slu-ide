package dev.sluide.databases.meta

import dev.sluide.databases.connect.DatabaseKind
import java.sql.Connection

enum class ObjectKind {
    SCHEMA,
    GROUP,
    TABLE,
    VIEW,
    MATERIALIZED_VIEW,
    COLUMN,
    PRIMARY_KEY,
    FOREIGN_KEY,
    INDEX,
    UNIQUE,
    CHECK,
    TRIGGER,
    FUNCTION,
    PROCEDURE,
    SEQUENCE,
    ENUM,
    DOMAIN,
    COMPOSITE_TYPE,
    EVENT,
}

/**
 * One node of a connection's object tree. name: the object's name (GROUP: its label, e.g. "tables"); table: the owning
 * table/view for columns, keys, indexes, checks, triggers; detail: the muted text after the name (column: "integer not
 * null default 0", FK: "(customer_id) → public.customer(id)", routine: "(integer, text) → integer", index: "(a, b)
 * unique"); ref: an identity that tells overloads apart (PostgreSQL routines: their identity arguments), else "".
 */
data class DbObject(
    val kind: ObjectKind,
    val schema: String?,
    val name: String,
    val table: String? = null,
    val detail: String = "",
    val ref: String = "",
    val children: List<DbObject> = emptyList(),
)

/**
 * Reads a database's catalog into [DbObject]s. All methods use the [Connection] the caller gives (on the session's
 * metadata thread), never open connections or threads, and let `SQLException`s propagate.
 */
interface Introspector {
    /** Schema names (MariaDB: databases) the user can see, sorted; without system schemas unless [includeSystem]. */
    fun schemas(
        connection: Connection,
        includeSystem: Boolean,
    ): List<String>

    /**
     * The GROUP nodes of a schema with all their children loaded, with a handful of catalog queries in total (not one
     * per table). Only non-empty groups, in this order: tables, views, materialized views (PG), routines, sequences,
     * types (PG), events (MariaDB). A table's children: its columns (ordinal order), then non-empty GROUPs "keys"
     * (PRIMARY_KEY, UNIQUE), "foreign keys", "indexes", "checks", "triggers". A view's/materialized view's children:
     * its columns.
     */
    fun schema(
        connection: Connection,
        schema: String,
    ): List<DbObject>

    /**
     * The DDL of [obj] as SQL text; every statement ends with `;` and a newline. An object dropped since the tree was
     * loaded gives `""` for every kind, never broken DDL.
     */
    fun ddl(
        connection: Connection,
        obj: DbObject,
    ): String

    /** The primary-key column names of a table in key order; empty when it has none. */
    fun primaryKey(
        connection: Connection,
        schema: String?,
        table: String,
    ): List<String>

    companion object {
        fun of(kind: DatabaseKind): Introspector =
            when (kind) {
                DatabaseKind.POSTGRESQL -> PostgresIntrospector
                DatabaseKind.MARIADB -> MariaDbIntrospector
            }
    }
}
