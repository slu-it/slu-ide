package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind

/** Quoting of identifiers in generated SQL (data tabs, edits, Generate SELECT, exports). */
object SqlNames {
    /** [identifier] quoted for [kind]: `"name"` for PostgreSQL, `` `name` `` for MariaDB, inner quotes doubled. */
    fun quote(
        kind: DatabaseKind,
        identifier: String,
    ): String {
        val q = if (kind == DatabaseKind.MARIADB) '`' else '"'
        return "$q${identifier.replace("$q", "$q$q")}$q"
    }

    /** `schema.name` with both parts quoted, or just the quoted name without a schema. */
    fun qualified(
        kind: DatabaseKind,
        schema: String?,
        name: String,
    ): String = if (schema.isNullOrEmpty()) quote(kind, name) else "${quote(kind, schema)}.${quote(kind, name)}"
}
