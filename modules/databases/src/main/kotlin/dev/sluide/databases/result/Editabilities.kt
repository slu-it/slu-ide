package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.ColumnInfo

/** Whether a result's grid can be edited, and the table and key its statements go to. */
sealed interface Editability {
    data class Editable(
        val ref: TableRef,
        val key: List<String>,
    ) : Editability

    data class ReadOnly(
        val reason: String,
    ) : Editability
}

object Editabilities {
    /** The single table all columns come from, or null (computed columns, joins). */
    fun singleTable(
        kind: DatabaseKind,
        columns: List<ColumnInfo>,
    ): TableRef? {
        val first = columns.firstOrNull() ?: return null
        val table = first.table
        if (table.isNullOrEmpty()) return null
        val sameTable = columns.all { it.table == table && it.schema == first.schema }
        return if (sameTable) TableRef(kind, first.schema, table) else null
    }

    /**
     * [key] is the table's primary key (empty = none). Reasons: "Read-only connection", "The result comes from several
     * tables or expressions", "The table has no primary key", "The primary key column <c> is not in the result".
     */
    fun check(
        kind: DatabaseKind,
        columns: List<ColumnInfo>,
        key: List<String>,
        readOnly: Boolean,
    ): Editability {
        if (readOnly) return Editability.ReadOnly("Read-only connection")
        val ref =
            singleTable(kind, columns)
                ?: return Editability.ReadOnly("The result comes from several tables or expressions")
        if (key.isEmpty()) return Editability.ReadOnly("The table has no primary key")
        val missing = key.firstOrNull { k -> columns.none { it.name == k } }
        if (missing != null) return Editability.ReadOnly("The primary key column $missing is not in the result")
        return Editability.Editable(ref, key)
    }
}
