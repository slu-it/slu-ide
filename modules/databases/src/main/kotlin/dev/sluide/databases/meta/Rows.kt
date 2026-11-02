package dev.sluide.databases.meta

import java.sql.Connection

/** One catalog result row by lower-case column label; the assembly functions work on these, not on a server. */
internal typealias Row = Map<String, Any?>

internal fun Row.str(key: String): String? = this[key]?.toString()

internal fun Row.text(key: String): String = str(key).orEmpty()

internal fun Row.flag(key: String): Boolean =
    when (val v = this[key]) {
        null -> false
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> v.toString().lowercase() in setOf("t", "true", "1", "yes")
    }

internal fun Row.int(key: String): Int = (this[key] as? Number)?.toInt() ?: str(key)?.toIntOrNull() ?: 0

/** Runs [sql] with the bind [params] and reads all rows; labels are lower-cased. */
internal fun Connection.rows(
    sql: String,
    vararg params: Any?,
): List<Row> =
    prepareStatement(sql).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
        st.executeQuery().use { rs ->
            val meta = rs.metaData
            val labels = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
            buildList {
                while (rs.next()) add(labels.mapIndexed { i, l -> l to rs.getObject(i + 1) }.toMap())
            }
        }
    }
