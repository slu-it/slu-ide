package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.sql.SqlNames
import java.sql.Types

/**
 * A statement with its bind parameters. `types[i]` is the `java.sql.Types` code for `setObject(i + 1, value, type)`,
 * or null for a plain `setObject`. On PostgreSQL every String parameter has [Types.OTHER] (type unknown: the server
 * infers it from the column, so jsonb, enum, inet, interval, xml, arrays and `char(n)` accept text); MariaDB gets
 * null throughout.
 *
 * [expectOneRow] holds for UPDATE and DELETE: the submit must roll the batch back unless each of them affected exactly
 * one row (a key that matches nothing or several rows means the data changed under the grid).
 */
data class BoundStatement(
    val sql: String,
    val params: List<Any?>,
    val types: List<Int?>,
    val kind: StatementKind,
) {
    val expectOneRow: Boolean get() = kind != StatementKind.INSERT
}

/** What a [BoundStatement] does. */
enum class StatementKind { UPDATE, INSERT, DELETE }

/**
 * What [DataEdits.accepted] leaves: the new [rows], and [needsRefresh] when a row without a key value (an added row
 * whose generated key is not known) was dropped, or a kept added row left columns out (server defaults such as
 * `now()` are not known here); the grid must be loaded again to show them.
 */
data class Accepted(
    val rows: List<List<Any?>>,
    val needsRefresh: Boolean,
)

/** Unsubmitted edits of an editable result. Rows 0 until originals.size are the originals (by index), added rows follow. */
class DataEdits(
    val target: Editability.Editable,
    val columns: List<ColumnInfo>,
    originals: List<List<Any?>>,
) {
    private class Row(
        val original: List<Any?>?,
        val values: MutableList<Any?>,
    ) {
        var deleted = false

        /** Columns of an added row that were given a value. */
        val given = mutableSetOf<Int>()
    }

    private val rows: MutableList<Row> = originals.map { Row(it, it.toMutableList()) }.toMutableList()

    val rowCount: Int get() = rows.size

    fun value(
        row: Int,
        column: Int,
    ): Any? = rows[row].values[column]

    /** Sets a cell; editing back to the original value un-marks it. Throws [IllegalStateException] on a deleted row. */
    fun edit(
        row: Int,
        column: Int,
        value: Any?,
    ) {
        val r = rows[row]
        check(!r.deleted) { "Row ${row + 1} is deleted" }
        if (r.original == null) {
            r.given += column
            r.values[column] = value
        } else {
            r.values[column] = if (same(r.original[column], value)) r.original[column] else value
        }
    }

    /** Appends an empty row and returns its index. */
    fun addRow(): Int {
        rows += Row(null, MutableList(columns.size) { null })
        return rows.lastIndex
    }

    /** Added rows disappear; originals are marked deleted. */
    fun deleteRows(rows: Collection<Int>) {
        for (i in rows.toSortedSet().reversed()) {
            val r = this.rows[i]
            if (r.original == null) this.rows.removeAt(i) else r.deleted = true
        }
    }

    /** Originals get their values back and are un-deleted; added rows disappear. */
    fun revert(rows: Collection<Int>) {
        for (i in rows.toSortedSet().reversed()) {
            val r = this.rows[i]
            if (r.original == null) {
                this.rows.removeAt(i)
            } else {
                r.deleted = false
                for (c in r.values.indices) r.values[c] = r.original[c]
            }
        }
    }

    fun revertAll() = revert(rows.indices.toList())

    fun isChanged(
        row: Int,
        column: Int,
    ): Boolean {
        val r = rows[row]
        return if (r.original == null) column in r.given else !same(r.original[column], r.values[column])
    }

    fun isDeleted(row: Int): Boolean = rows[row].deleted

    fun isAdded(row: Int): Boolean = rows[row].original == null

    val pending: Boolean
        get() =
            rows.any { r ->
                r.deleted || r.original == null ||
                    r.values.indices.any { !same(r.original[it], r.values[it]) }
            }

    /** DELETEs, then UPDATEs, then INSERTs; WHERE on the original key values; INSERT only the columns given a value; identifiers via SqlNames. */
    fun statements(): List<BoundStatement> {
        val kind = target.ref.kind
        val table = target.ref.qualified
        val keyIndexes = target.key.map { k -> columns.indexOfFirst { it.name == k } }
        val where = target.key.joinToString(" AND ") { "${SqlNames.quote(kind, it)} = ?" }
        val deletes = mutableListOf<BoundStatement>()
        val updates = mutableListOf<BoundStatement>()
        val inserts = mutableListOf<BoundStatement>()
        for (r in rows) {
            val original = r.original
            if (original == null) {
                inserts += insert(kind, table, r)
                continue
            }
            val keyValues = keyIndexes.map { original[it] }
            check(keyValues.none { it == null }) { "Row ${rows.indexOf(r) + 1} has no key value" }
            if (r.deleted) {
                deletes += bound(kind, "DELETE FROM $table WHERE $where", keyValues, StatementKind.DELETE)
                continue
            }
            val changed = columns.indices.filter { !same(original[it], r.values[it]) }
            if (changed.isEmpty()) continue
            val set = changed.joinToString(", ") { "${SqlNames.quote(kind, columns[it].name)} = ?" }
            updates +=
                bound(
                    kind,
                    "UPDATE $table SET $set WHERE $where",
                    changed.map { r.values[it] } + keyValues,
                    StatementKind.UPDATE,
                )
        }
        return deletes + updates + inserts
    }

    private fun bound(
        kind: DatabaseKind,
        sql: String,
        params: List<Any?>,
        statementKind: StatementKind,
    ): BoundStatement {
        val types = params.map { if (kind == DatabaseKind.POSTGRESQL && it is String) Types.OTHER else null }
        return BoundStatement(sql, params, types, statementKind)
    }

    private fun insert(
        kind: DatabaseKind,
        table: String,
        r: Row,
    ): BoundStatement {
        val given = columns.indices.filter { it in r.given }
        if (given.isEmpty()) {
            val sql =
                if (kind ==
                    DatabaseKind.MARIADB
                ) {
                    "INSERT INTO $table () VALUES ()"
                } else {
                    "INSERT INTO $table DEFAULT VALUES"
                }
            return bound(kind, sql, emptyList(), StatementKind.INSERT)
        }
        val names = given.joinToString(", ") { SqlNames.quote(kind, columns[it].name) }
        val marks = given.joinToString(", ") { "?" }
        val sql = "INSERT INTO $table ($names) VALUES ($marks)"
        return bound(kind, sql, given.map { r.values[it] }, StatementKind.INSERT)
    }

    /**
     * After a successful submit: edited values become the originals, deleted rows are removed, added rows become
     * originals. A row with a null key column (an added row whose generated key is unknown) is not kept, because
     * later edits of it could not find it: it is dropped and [Accepted.needsRefresh] is set;
     * so it is when an added row left columns out.
     */
    fun accepted(): Accepted {
        val keyIndexes = target.key.map { k -> columns.indexOfFirst { it.name == k } }
        val kept = rows.filter { !it.deleted }
        val result = kept.filter { r -> keyIndexes.none { r.values[it] == null } }.map { it.values.toList() }
        rows.clear()
        rows += result.map { Row(it, it.toMutableList()) }
        val leftOut = kept.any { it.original == null && it.given.size < columns.size }
        return Accepted(result, result.size != kept.size || leftOut)
    }

    private fun same(
        a: Any?,
        b: Any?,
    ): Boolean =
        when {
            a is ByteArray && b is ByteArray -> a.contentEquals(b)
            a is Number && b is Number -> ClientSort.compareNumbers(a, b) == 0
            else -> a == b
        }
}
