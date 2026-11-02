package dev.sluide.databases.session

import org.postgresql.PGResultSetMetaData
import org.postgresql.util.PGobject
import java.math.BigInteger
import java.sql.Blob
import java.sql.Clob
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLXML
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.util.UUID

/**
 * Reads columns and rows of a result set into plain JDK values on the session thread, so that no JDBC object leaves
 * it (ADR 0121).
 */
internal object JdbcValues {
    /** The columns of [resultSet]; PostgreSQL's base table, schema and column come from `PGResultSetMetaData`. */
    fun columns(resultSet: ResultSet): List<ColumnInfo> {
        val meta = resultSet.metaData
        val pg =
            if (meta.isWrapperFor(
                    PGResultSetMetaData::class.java,
                )
            ) {
                meta.unwrap(PGResultSetMetaData::class.java)
            } else {
                null
            }
        return (1..meta.columnCount).map { i ->
            val name = meta.getColumnName(i)
            // MariaDB has no schemas: its databases are JDBC catalogs.
            val schema =
                if (pg !=
                    null
                ) {
                    pg.getBaseSchemaName(i)
                } else {
                    meta.getSchemaName(i).ifBlankNull() ?: meta.getCatalogName(i)
                }
            ColumnInfo(
                name = pg?.getBaseColumnName(i).ifBlankNull() ?: name,
                label = meta.getColumnLabel(i).ifBlankNull() ?: name,
                typeName = meta.getColumnTypeName(i) ?: "",
                jdbcType = meta.getColumnType(i),
                schema = schema.ifBlankNull(),
                table = (if (pg != null) pg.getBaseTableName(i) else meta.getTableName(i)).ifBlankNull(),
                nullable = meta.isNullable(i) != ResultSetMetaData.columnNoNulls,
                autoIncrement = meta.isAutoIncrement(i),
            )
        }
    }

    /** The current row of [resultSet] as plain values. */
    fun row(
        resultSet: ResultSet,
        columns: List<ColumnInfo>,
    ): List<Any?> = columns.mapIndexed { i, column -> value(resultSet, i + 1, column) }

    private fun value(
        resultSet: ResultSet,
        index: Int,
        column: ColumnInfo,
    ): Any? {
        val temporal = temporalClass(column)
        val raw =
            try {
                if (temporal != null) resultSet.getObject(index, temporal) else resultSet.getObject(index)
            } catch (_: SQLException) {
                // Values the driver cannot map (PostgreSQL money, MariaDB zero dates) are shown as the server sends them.
                return resultSet.getString(index)
            }
        return plain(raw)
    }

    /** The `java.time` class a temporal column is read as; PostgreSQL reports `timestamptz` as TIMESTAMP. */
    internal fun temporalClass(column: ColumnInfo): Class<*>? {
        val type = column.typeName.lowercase()
        return when {
            type == "timestamptz" || column.jdbcType == Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime::class.java
            type == "timetz" || column.jdbcType == Types.TIME_WITH_TIMEZONE -> OffsetTime::class.java
            column.jdbcType == Types.TIMESTAMP -> LocalDateTime::class.java
            column.jdbcType == Types.TIME -> LocalTime::class.java
            column.jdbcType == Types.DATE && type != "year" -> LocalDate::class.java
            else -> null
        }
    }

    /** [value] as a plain JDK value: JDBC objects (arrays, LOBs, PGobject) are read out and freed. */
    internal fun plain(value: Any?): Any? =
        when (value) {
            null, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
            is java.math.BigDecimal, is BigInteger, is ByteArray, is UUID -> value
            is LocalDate, is LocalTime, is LocalDateTime, is OffsetDateTime, is OffsetTime -> value
            is java.sql.Timestamp -> value.toLocalDateTime()
            is java.sql.Date -> value.toLocalDate()
            is java.sql.Time -> value.toLocalTime()
            is PGobject -> value.value
            is java.sql.Array -> freeing({ value.free() }) { arrayList(value.array) }
            is Blob -> freeing({ value.free() }) { value.getBytes(1, value.length().toInt()) }
            is Clob -> freeing({ value.free() }) { value.getSubString(1, value.length().toInt()) }
            is SQLXML -> freeing({ value.free() }) { value.string }
            is Array<*> -> arrayList(value)
            is CharArray -> String(value)
            is IntArray, is LongArray, is ShortArray, is DoubleArray, is FloatArray, is BooleanArray -> arrayList(value)
            else -> value.toString()
        }

    private fun arrayList(array: Any?): List<Any?>? =
        when (array) {
            null -> null
            is Array<*> -> array.map { plain(it) }
            is IntArray -> array.toList()
            is LongArray -> array.toList()
            is ShortArray -> array.toList()
            is DoubleArray -> array.toList()
            is FloatArray -> array.toList()
            is BooleanArray -> array.toList()
            else -> listOf(plain(array))
        }

    private fun <T> freeing(
        free: () -> Unit,
        read: () -> T,
    ): T =
        try {
            read()
        } finally {
            runCatching(free)
        }

    private fun String?.ifBlankNull(): String? = if (isNullOrBlank()) null else this
}
