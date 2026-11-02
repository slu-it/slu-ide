package dev.sluide.databases.session

import org.postgresql.core.BaseConnection
import org.postgresql.core.TransactionState
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a fake statement does for one SQL text. */
sealed interface FakeResult {
    /** Integer columns `c1`, `c2`, … of table `t`; reading row [failAt] (0-based) throws [failure]. */
    data class Rows(
        val rows: List<List<Any?>>,
        val failAt: Int? = null,
        val failure: SQLException = SQLException("read failed", "XX000"),
    ) : FakeResult

    data class Count(
        val count: Long,
    ) : FakeResult

    data class Fails(
        val error: SQLException,
    ) : FakeResult

    /** Signals [started], then waits until the statement is cancelled and throws PostgreSQL's cancel error. */
    data class BlocksUntilCancel(
        val started: CountDownLatch = CountDownLatch(1),
    ) : FakeResult

    /** Signals [started], then waits for [gate] and gives an update count of 1. */
    data class Gated(
        val started: CountDownLatch = CountDownLatch(1),
        val gate: CountDownLatch = CountDownLatch(1),
    ) : FakeResult
}

/**
 * JDBC objects as JDK proxies that record what is done to them in [log] (`session: autoCommit=false`, `session:
 * execute SELECT 1`, `session: commit`, …). [results] decides what a statement's SQL gives. The connections are
 * pgjdbc `BaseConnection`s whose transaction state follows pgjdbc (a statement with auto-commit off or BEGIN opens
 * one, an error fails it, COMMIT / ROLLBACK and turning auto-commit on end it) while [exposesTransactionState].
 */
class FakeDatabase(
    var results: (String) -> FakeResult = { FakeResult.Count(1) },
) {
    val log = CopyOnWriteArrayList<String>()

    /** Whether `isValid` answers true. */
    @Volatile var valid = true

    /** Whether the connections unwrap to pgjdbc's `BaseConnection`; MariaDB sessions get plain connections. */
    @Volatile var exposesTransactionState = true

    /** Runs when a connection is aborted. */
    @Volatile var onAbort: () -> Unit = {}

    /** Whether opening another connection ([reconnect]) fails. */
    @Volatile var connectFails = false

    val session = connection("session")
    val metadata = connection("metadata")

    /** The passwords given to [reconnect], as the very arrays. */
    val reconnectPasswords = CopyOnWriteArrayList<CharArray?>()

    /** Opens another metadata connection (`metadata: connected`), or fails like a server that is gone. */
    fun reconnect(password: CharArray?): Connection {
        reconnectPasswords += password
        if (connectFails) throw SQLException("Connection refused", "08001")
        log += "metadata: connected"
        return connection("metadata")
    }

    /** The SQL texts the session connection executed, in order. */
    fun executed(): List<String> =
        log.filter { it.startsWith("session: execute ") }.map { it.removePrefix("session: execute ") }

    private class Transaction {
        @Volatile var autoCommit = true

        @Volatile var state = TransactionState.IDLE

        fun beforeStatement(sql: String) {
            if (!autoCommit && state == TransactionState.IDLE) state = TransactionState.OPEN
            val command = sql.trim().uppercase()
            when {
                command.startsWith("BEGIN") || command.startsWith("START TRANSACTION") -> state = TransactionState.OPEN
                command.startsWith("COMMIT") || command.startsWith("ROLLBACK") -> state = TransactionState.IDLE
            }
        }

        fun failed() {
            if (state != TransactionState.IDLE) state = TransactionState.FAILED
        }
    }

    private fun connection(name: String): Connection {
        val transaction = Transaction()
        var closed = false
        return proxy<BaseConnection> { self, method, args ->
            when (method) {
                "createStatement" -> {
                    statement(name, transaction)
                }

                "setAutoCommit" -> {
                    val on = args[0] as Boolean
                    if (on && !transaction.autoCommit && transaction.state != TransactionState.IDLE) {
                        log += "$name: commit by auto-commit"
                        transaction.state = TransactionState.IDLE
                    }
                    transaction.autoCommit = on
                    log += "$name: autoCommit=$on"
                }

                "getAutoCommit" -> {
                    transaction.autoCommit
                }

                "getTransactionState" -> {
                    transaction.state
                }

                "setReadOnly" -> {
                    log += "$name: readOnly=${args[0]}"
                }

                "commit", "rollback" -> {
                    transaction.state = TransactionState.IDLE
                    log += "$name: $method"
                }

                "close" -> {
                    closed = true
                    log += "$name: close"
                }

                "abort" -> {
                    closed = true
                    log += "$name: abort"
                    onAbort()
                }

                "isClosed" -> {
                    closed
                }

                "isValid" -> {
                    valid && !closed
                }

                "isWrapperFor" -> {
                    exposesTransactionState && (args[0] as Class<*>).isInstance(self)
                }

                "unwrap" -> {
                    self
                }

                else -> {
                    unsupported(method)
                }
            }
        }
    }

    private fun statement(
        connection: String,
        transaction: Transaction,
    ): Statement {
        val cancelled = CountDownLatch(1)
        var resultSet: ResultSet? = null
        var count = -1L
        var maxRows = 0L
        var closed = false

        fun run(sql: String): FakeResult {
            transaction.beforeStatement(sql)
            val result = results(sql)
            if (result is FakeResult.Fails) transaction.failed()
            return result
        }
        return proxy { _, method, args ->
            when (method) {
                "setFetchSize" -> {
                    log += "$connection: fetchSize=${args[0]}"
                }

                "setQueryTimeout" -> {
                    log += "$connection: queryTimeout=${args[0]}"
                }

                "setMaxRows" -> {
                    maxRows = (args[0] as Int).toLong()
                    log += "$connection: maxRows=$maxRows"
                }

                // As pgjdbc: not implemented.
                "setLargeMaxRows" -> {
                    throw SQLException("Method setLargeMaxRows is not yet implemented.", "0A000")
                }

                "execute" -> {
                    val sql = args[0] as String
                    log += "$connection: execute $sql"
                    when (val result = run(sql)) {
                        is FakeResult.Rows -> {
                            val rows = if (maxRows > 0) result.rows.take(maxRows.toInt()) else result.rows
                            resultSet = resultSet(connection, result.copy(rows = rows))
                            true
                        }

                        is FakeResult.Count -> {
                            count = result.count
                            false
                        }

                        is FakeResult.Fails -> {
                            throw result.error
                        }

                        is FakeResult.BlocksUntilCancel -> {
                            result.started.countDown()
                            check(cancelled.await(10, TimeUnit.SECONDS)) { "never cancelled" }
                            transaction.failed()
                            throw SQLException("ERROR: canceling statement due to user request", "57014")
                        }

                        is FakeResult.Gated -> {
                            result.started.countDown()
                            check(result.gate.await(10, TimeUnit.SECONDS)) { "gate never opened" }
                            count = 1
                            false
                        }
                    }
                }

                "executeQuery" -> {
                    val sql = args[0] as String
                    log += "$connection: executeQuery $sql"
                    when (val result = run(sql)) {
                        is FakeResult.Rows -> resultSet(connection, result)
                        is FakeResult.Fails -> throw result.error
                        else -> throw SQLException("fake: $sql gives no rows")
                    }
                }

                "getResultSet" -> {
                    resultSet
                }

                "getLargeUpdateCount" -> {
                    count
                }

                "cancel" -> {
                    log += "$connection: cancel"
                    cancelled.countDown()
                }

                "close" -> {
                    closed = true
                    log += "$connection: close statement"
                }

                "isClosed" -> {
                    closed
                }

                else -> {
                    unsupported(method)
                }
            }
        }
    }

    private fun resultSet(
        connection: String,
        result: FakeResult.Rows,
    ): ResultSet {
        val rows = result.rows
        val width = rows.firstOrNull()?.size ?: 1
        var row = -1
        return proxy { _, method, args ->
            when (method) {
                "next" -> {
                    if (++row == result.failAt) throw result.failure
                    row < rows.size
                }

                "getObject" -> {
                    rows[row][(args[0] as Int) - 1]
                }

                "getString" -> {
                    rows[row][(args[0] as Int) - 1]?.toString()
                }

                "getMetaData" -> {
                    metaData(width)
                }

                "close" -> {
                    log += "$connection: close result"
                }

                "isWrapperFor" -> {
                    false
                }

                else -> {
                    unsupported(method)
                }
            }
        }
    }

    private fun metaData(width: Int): ResultSetMetaData =
        proxy { _, method, args ->
            when (method) {
                "getColumnCount" -> width
                "getColumnName", "getColumnLabel" -> "c${args[0]}"
                "getColumnTypeName" -> "int4"
                "getColumnType" -> Types.INTEGER
                "getSchemaName" -> "public"
                "getCatalogName" -> ""
                "getTableName" -> "t"
                "isNullable" -> ResultSetMetaData.columnNullable
                "isAutoIncrement" -> false
                "isWrapperFor" -> false
                else -> unsupported(method)
            }
        }

    private fun unsupported(method: String): Nothing = throw UnsupportedOperationException("fake JDBC: $method")

    private inline fun <reified T> proxy(
        crossinline handler: (self: Any, method: String, args: Array<Any?>) -> Any?,
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "toString" -> "fake ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                else -> handler(self, method.name, args ?: emptyArray()).takeUnless { it == Unit }
            }
        } as T
}

/** [count] rows of [width] integer columns: row i holds i*10+1, i*10+2, … */
fun fakeRows(
    count: Int,
    width: Int = 2,
): List<List<Any?>> = List(count) { i -> List(width) { c -> i * 10 + c + 1 } }
