package dev.sluide.databases.meta

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement

/**
 * A JDBC connection that answers catalog queries from memory, for the [Introspector] tests (the real servers of
 * `IntrospectionSmokeTest` are opt-in). A result is its column labels (case as the driver reports them) and rows; what
 * it relies on follows the JDBC contract: `prepareStatement` + `setObject(1-based)` + `executeQuery`, labels from the
 * result metadata, `getObject(index)` / `getString(label)`, `SQLException.errorCode`. Not checked against a real
 * driver in this sandbox (no server, no image). What it returns was compared with how the code reads it ([PgQueries],
 * [MariaQueries], [rows] and `IntrospectionSmokeTest`): lower-cased labels, any `Number` for ints and flags, rows in
 * the order of the queries' `order by`, the `SHOW CREATE` labels including a trigger's trailing `Created` column. The
 * check against real servers (`SLUIDE_DB_SMOKE=1`, with the images) is still open.
 */
internal class CatalogJdbc {
    class Result(
        val labels: List<String>,
        val rows: List<List<Any?>>,
    )

    /** What each SQL text answers; a text that is not set answers no columns and no rows. */
    val results = mutableMapOf<String, Result>()

    /** SQL texts that throw this when executed. */
    val failures = mutableMapOf<String, SQLException>()

    /** Every executed SQL text with the parameters bound to it (none for a plain statement). */
    val calls = mutableListOf<Pair<String, List<Any?>>>()

    /** The number of statements and result sets closed. */
    var closed = 0
        private set

    /** Sets the answer of [sql] from maps; the labels are the keys of the first row (or [labels]). */
    fun answer(
        sql: String,
        vararg rows: Map<String, Any?>,
        labels: List<String> =
            rows
                .firstOrNull()
                ?.keys
                ?.toList()
                .orEmpty(),
    ) {
        results[sql] = Result(labels, rows.map { row -> labels.map { row[it] } })
    }

    val connection: Connection =
        proxy { _, method, args ->
            when (method) {
                "prepareStatement" -> prepared(args[0] as String)
                "createStatement" -> plain()
                else -> throw UnsupportedOperationException(method)
            }
        }

    private fun execute(
        sql: String,
        params: List<Any?>,
    ): ResultSet {
        calls += sql to params
        failures[sql]?.let { throw it }
        return resultSet(results[sql] ?: Result(emptyList(), emptyList()))
    }

    private fun prepared(sql: String): PreparedStatement {
        val params = sortedMapOf<Int, Any?>()
        return proxy { _, method, args ->
            when (method) {
                "setObject" -> {
                    // JDBC counts parameters from 1; drivers throw for any other index.
                    val index = args[0] as Int
                    if (index < 1) throw SQLException("The column index is out of range: $index", "07009")
                    params[index] = args[1]
                }

                "executeQuery" -> {
                    execute(sql, params.values.toList())
                }

                "close" -> {
                    closed++
                }

                else -> {
                    throw UnsupportedOperationException(method)
                }
            }
        }
    }

    private fun plain(): Statement =
        proxy { _, method, args ->
            when (method) {
                "executeQuery" -> execute(args[0] as String, emptyList())
                "close" -> closed++
                else -> throw UnsupportedOperationException(method)
            }
        }

    private fun resultSet(result: Result): ResultSet {
        var next = -1
        val meta =
            proxy<ResultSetMetaData> { _, method, args ->
                when (method) {
                    "getColumnCount" -> result.labels.size
                    "getColumnLabel" -> result.labels[(args[0] as Int) - 1]
                    else -> throw UnsupportedOperationException(method)
                }
            }
        return proxy { _, method, args ->
            when (method) {
                "getMetaData" -> meta
                "next" -> ++next < result.rows.size
                "getObject" -> result.rows[next][(args[0] as Int) - 1]
                "getString" -> result.rows[next][result.labels.indexOf(args[0] as String)]?.toString()
                "close" -> closed++
                else -> throw UnsupportedOperationException(method)
            }
        }
    }

    private inline fun <reified T : Any> proxy(crossinline handler: (Any, String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            try {
                handler(self, method.name, args ?: emptyArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T
}
