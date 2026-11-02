package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.sql.SqlNames
import java.math.BigDecimal

enum class ExportFormat(
    val label: String,
    val extension: String,
) {
    CSV("CSV", "csv"),
    TSV("TSV", "tsv"),
    JSON("JSON", "json"),
    SQL_INSERT("SQL INSERTs", "sql"),
    MARKDOWN("Markdown table", "md"),
}

/** Result rows as text in an export format. Every line, also the last, ends with a line break (JSON: after the closing bracket). */
object ResultExport {
    /** [tableName]: used as is for SQL INSERTs (else "result"). */
    fun write(
        format: ExportFormat,
        kind: DatabaseKind,
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
        tableName: String?,
    ): String =
        when (format) {
            ExportFormat.CSV -> delimited(columns, rows, ",", "\r\n", ",\"\r\n")
            ExportFormat.TSV -> delimited(columns, rows, "\t", "\n", "\t\"\r\n")
            ExportFormat.JSON -> json(columns, rows)
            ExportFormat.SQL_INSERT -> inserts(kind, columns, rows, tableName ?: "result")
            ExportFormat.MARKDOWN -> markdown(columns, rows)
        }

    private fun delimited(
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
        separator: String,
        lineEnd: String,
        quoteTriggers: String,
    ): String {
        fun field(text: String) =
            if (text.any { it in quoteTriggers }) "\"" + text.replace("\"", "\"\"") + "\"" else text
        val sb = StringBuilder()
        sb.append(columns.joinToString(separator) { field(it.label) }).append(lineEnd)
        for (row in rows) sb.append(row.joinToString(separator) { field(CellFormat.full(it)) }).append(lineEnd)
        return sb.toString()
    }

    private fun json(
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
    ): String {
        if (rows.isEmpty()) return "[]\n"
        val keys = columns.map { jsonString(it.label) }
        val lines =
            rows.map { row ->
                keys.indices.joinToString(", ", "  {", "}") { "${keys[it]}: ${jsonValue(row[it])}" }
            }
        return lines.joinToString(",\n", "[\n", "\n]\n")
    }

    private fun jsonValue(value: Any?): String =
        when {
            value == null -> "null"
            value is Boolean -> value.toString()
            value is BigDecimal -> value.toPlainString()
            value is Number && isFinite(value) -> value.toString()
            else -> jsonString(CellFormat.full(value))
        }

    private fun isFinite(n: Number): Boolean = (n !is Double || n.isFinite()) && (n !is Float || n.isFinite())

    private fun jsonString(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun inserts(
        kind: DatabaseKind,
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
        tableName: String,
    ): String {
        val names = columns.joinToString(", ") { SqlNames.quote(kind, it.label) }
        val sb = StringBuilder()
        for (row in rows) {
            sb
                .append("INSERT INTO ")
                .append(tableName)
                .append(" (")
                .append(names)
                .append(") VALUES (")
            sb.append(row.joinToString(", ") { sqlLiteral(kind, it) }).append(");\n")
        }
        return sb.toString()
    }

    private fun sqlLiteral(
        kind: DatabaseKind,
        value: Any?,
    ): String {
        val maria = kind == DatabaseKind.MARIADB
        return when {
            value == null -> {
                "NULL"
            }

            value is Boolean -> {
                if (maria) (if (value) "1" else "0") else value.toString().uppercase()
            }

            value is BigDecimal -> {
                value.toPlainString()
            }

            value is Number && isFinite(value) -> {
                value.toString()
            }

            value is ByteArray -> {
                if (maria) "X'${CellFormat.hex(value)}'" else "E'\\\\x${CellFormat.hex(value)}'::bytea"
            }

            else -> {
                // Both kinds double the backslash; PostgreSQL's E'…' does not depend on standard_conforming_strings.
                val escaped = CellFormat.full(value).replace("\\", "\\\\").replace("'", "''")
                (if (maria) "'" else "E'") + escaped + "'"
            }
        }
    }

    private fun markdown(
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
    ): String {
        fun cell(text: String) =
            text
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r\n", "<br>")
                .replace("\n", "<br>")
                .replace("\r", "<br>")
        val sb = StringBuilder()
        sb.append(columns.joinToString(" | ", "| ", " |") { cell(it.label) }).append('\n')
        sb.append(columns.joinToString(" | ", "| ", " |") { "---" }).append('\n')
        for (row in rows) sb.append(row.joinToString(" | ", "| ", " |") { cell(CellFormat.full(it)) }).append('\n')
        return sb.toString()
    }
}
