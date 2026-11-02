package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.sql.SqlNames

/** A table of a database, as the data tab shows it. */
data class TableRef(
    val kind: DatabaseKind,
    val schema: String?,
    val table: String,
) {
    /** `schema.table` quoted for [kind]. */
    val qualified: String get() = SqlNames.qualified(kind, schema, table)
}

/** The SELECT of a table's data tab. */
object TableDataQuery {
    /**
     * `SELECT * FROM <qualified> [WHERE (<where>)] [ORDER BY <orderBy>]`, each clause on its own line and the closing
     * parenthesis too, so that a trailing `-- comment` in the input cannot swallow what follows. [where] and
     * [orderBy] are the user's SQL text without a trailing `;`; blank = left out. The last clause ends with a line
     * break, so text appended to the query (LIMIT, OFFSET) starts on a new line.
     */
    fun select(
        ref: TableRef,
        where: String,
        orderBy: String,
    ): String {
        val sb = StringBuilder("SELECT * FROM ").append(ref.qualified)
        val w = clean(where)
        if (w.isNotEmpty()) sb.append("\nWHERE (").append(w).append("\n)")
        val o = clean(orderBy)
        if (o.isNotEmpty()) sb.append("\nORDER BY ").append(o).append('\n')
        return sb.toString()
    }

    private fun clean(text: String): String {
        var t = text.trim()
        while (t.endsWith(";")) t = t.dropLast(1).trimEnd()
        return t
    }

    /** The ORDER BY text for a header click: quoted column + " ASC"/" DESC". */
    fun orderBy(
        kind: DatabaseKind,
        column: String,
        ascending: Boolean,
    ): String = SqlNames.quote(kind, column) + if (ascending) " ASC" else " DESC"
}
