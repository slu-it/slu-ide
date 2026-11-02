package dev.sluide.databases.result

import dev.sluide.databases.session.ColumnInfo
import java.sql.Types

/** Swaps rows and columns of a result (record view). */
object Transpose {
    /** One row per column: first column "Column" (labels), then one column per row "1", "2", …. */
    fun of(
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
    ): Pair<List<ColumnInfo>, List<List<Any?>>> {
        val header = ColumnInfo("Column", "Column", "text", Types.VARCHAR, null, null, false, false)
        val rowColumns =
            rows.indices.map { ColumnInfo("${it + 1}", "${it + 1}", "", Types.OTHER, null, null, true, false) }
        val transposed = columns.mapIndexed { c, col -> listOf<Any?>(col.label) + rows.map { it[c] } }
        return (listOf(header) + rowColumns) to transposed
    }
}
