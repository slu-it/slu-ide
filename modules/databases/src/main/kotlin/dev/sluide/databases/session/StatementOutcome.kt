package dev.sluide.databases.session

/** A column of a result, from the driver's `ResultSetMetaData`. */
data class ColumnInfo(
    /** The column's own name: PostgreSQL's base column, else the driver's column name. */
    val name: String,
    /** The name shown in the header: the alias when the query gives one. */
    val label: String,
    /** The server's type name, e.g. `int4`, `jsonb`, `VARCHAR`. */
    val typeName: String,
    /** The `java.sql.Types` constant the driver reports. */
    val jdbcType: Int,
    /** The base table's schema (MariaDB: database) where the driver knows it, else null. */
    val schema: String?,
    /** The base table where the driver knows it (not an alias), else null. */
    val table: String?,
    val nullable: Boolean,
    val autoIncrement: Boolean,
)

/**
 * Rows `firstRow until firstRow + rows.size` of a result, with values copied into plain JDK types (String, Boolean,
 * numbers, ByteArray, java.time, UUID, List for arrays); [last] once the result has no rows after these.
 */
data class Page(
    val index: Int,
    val firstRow: Int,
    val rows: List<List<Any?>>,
    val last: Boolean,
)

/** What running one statement gave. */
sealed interface StatementOutcome {
    val sql: String

    /** Wall time of running the statement and reading its first page. */
    val millis: Long

    /** A result set: its [firstPage] is read, the rest comes page by page from [cursor]. */
    data class Rows(
        override val sql: String,
        override val millis: Long,
        val columns: List<ColumnInfo>,
        val cursor: RowCursor,
        val firstPage: Page,
    ) : StatementOutcome

    /** An update count (0 for DDL). */
    data class Updated(
        override val sql: String,
        override val millis: Long,
        val count: Long,
    ) : StatementOutcome

    /** position: 0-based offset into sql when the server reports one (PostgreSQL Position), else null. */
    data class Failed(
        override val sql: String,
        override val millis: Long,
        val message: String,
        val sqlState: String?,
        val position: Int?,
    ) : StatementOutcome

    /** Stopped by [DbSession.cancel]. */
    data class Cancelled(
        override val sql: String,
        override val millis: Long,
    ) : StatementOutcome
}
