package dev.sluide.databases.session

import dev.sluide.databases.connect.DatabaseKind
import org.mariadb.jdbc.client.result.Result
import org.mariadb.jdbc.util.constants.ServerStatus
import org.postgresql.core.BaseConnection
import org.postgresql.core.TransactionState
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement

private val log = System.getLogger(SessionStatements::class.java.name)

/** Starting and ending statements on the session connection of session `db#`[number]; session thread only. */
internal class SessionStatements(
    private val session: Connection,
    private val kind: DatabaseKind,
    private val number: Int,
    /** Called once the statement exists, before it runs: from then on it can be cancelled. */
    private val onStart: (Statement) -> Unit,
    /** Called when a statement without rows is done, before it is closed: a cancel can no longer reach it. */
    private val onEnd: () -> Unit,
) {
    sealed interface Started {
        class Result(
            val live: LiveResult,
        ) : Started

        class Count(
            val count: Long,
        ) : Started
    }

    /**
     * Runs [sql] with [fetchSize] and the timeout, if any. With [autoTransaction] (AUTO mode, a PostgreSQL query, no
     * transaction open) it runs with auto-commit off, because PostgreSQL streams rows by the fetch size only then; the
     * transaction ends with a COMMIT when the result closes (its end read, the next statement, the cursor closed). It
     * is not read-only and is committed because `isQuery` cannot see every write (`SELECT nextval('s')`, a function
     * that writes), which auto-commit would have kept; row locks of `SELECT … FOR UPDATE` last until the result
     * closes. Without it PostgreSQL reads the whole result into memory, as inside a transaction the user opened with
     * BEGIN in AUTO mode: turning auto-commit off there and on again would commit the user's transaction. On failure
     * the statement is closed, such a transaction rolled back, and the error rethrown. With [rowLimit] (the fetch-all
     * cap, see `JdbcSession.rowLimit`) the driver reads at most one row more than it.
     */
    fun start(
        sql: String,
        autoTransaction: Boolean,
        fetchSize: Int,
        timeout: Int,
        rowLimit: Int? = null,
    ): Started {
        var statement: Statement? = null
        try {
            if (autoTransaction) session.autoCommit = false
            statement = session.createStatement()
            statement.fetchSize = fetchSize
            if (timeout > 0) statement.queryTimeout = timeout
            // pgjdbc does not implement setLargeMaxRows (SQLState 0A000).
            if (rowLimit !=
                null
            ) {
                statement.maxRows = (rowLimit.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            onStart(statement)
            if (statement.execute(sql)) {
                return Started.Result(LiveResult(statement, statement.resultSet, autoTransaction, rowLimit))
            }
            val count = updateCount(statement)
            onEnd()
            statement.close()
            if (autoTransaction) finishAutoTransaction(commit = true)
            return Started.Count(count)
        } catch (e: Exception) {
            statement?.let { quietly { it.close() } }
            if (autoTransaction) quietly { finishAutoTransaction(commit = false) }
            throw e
        }
    }

    /**
     * Closes a result set and ends its own transaction; [failed] rolls it back. MariaDB Connector/J streams a result
     * with a fetch size, and closing it with unread rows reads them all off the socket first (`skipRemaining`): about
     * a second for 3 million short rows on a local server, longer over a network. So a MariaDB result the driver has
     * not received completely (`Result.loaded()`) is cancelled first (`KILL QUERY` over a short extra connection,
     * about 2 ms locally) and the close reads only what was already sent; a cancel that comes after the server
     * finished sending does not touch the next statement (smoke-tested). PostgreSQL closes its portal in one round
     * trip. Never throws.
     */
    fun end(
        live: LiveResult,
        reachedEnd: Boolean,
        failed: Boolean,
    ) {
        try {
            if (!reachedEnd && !failed && kind == DatabaseKind.MARIADB && !received(live.resultSet)) {
                quietly { live.statement.cancel() }
            }
            quietly { live.resultSet.close() }
            quietly { live.statement.close() }
            if (live.autoTransaction) finishAutoTransaction(commit = !failed)
        } catch (e: SQLException) {
            log.log(
                System.Logger.Level.WARNING,
                "db#$number ending a result's transaction failed (SQLState ${e.sqlState})",
            )
        }
    }

    /**
     * Whether MariaDB's driver holds the whole result already, so closing it reads nothing more. `Result` is in an
     * internal package of Connector/J (not exported by its module-info): this works on the class path only, as the
     * app runs; on the module path `unwrap` would fail and every unread result would be cancelled.
     */
    private fun received(resultSet: ResultSet): Boolean =
        try {
            resultSet.isWrapperFor(Result::class.java) && resultSet.unwrap(Result::class.java).loaded()
        } catch (_: SQLException) {
            false
        } catch (_: LinkageError) {
            false // IllegalAccessError on the module path
        }

    /**
     * Whether the server has a transaction open on the session connection, from what the driver knows without a round
     * trip: pgjdbc's transaction state (open or failed), MariaDB's `SERVER_STATUS_IN_TRANS` flag of the last reply (a
     * query of `@@in_transaction` would first read the rest of an open streamed result). Null when the driver is not
     * one of these.
     */
    fun serverInTransaction(): Boolean? =
        try {
            when {
                session.isWrapperFor(BaseConnection::class.java) -> {
                    session.unwrap(BaseConnection::class.java).transactionState != TransactionState.IDLE
                }

                session.isWrapperFor(org.mariadb.jdbc.Connection::class.java) -> {
                    val status = session.unwrap(org.mariadb.jdbc.Connection::class.java).context.serverStatus
                    (status and ServerStatus.IN_TRANSACTION.toInt()) != 0
                }

                else -> {
                    null
                }
            }
        } catch (_: SQLException) {
            null
        }

    /**
     * Whether PostgreSQL's current transaction wrote anything: `txid_current_if_assigned()` (PostgreSQL 10+) is
     * non-null exactly then. Null when it cannot tell (aborted transaction, older server).
     */
    fun serverWrote(): Boolean? =
        try {
            session.createStatement().use { statement ->
                statement.executeQuery("SELECT txid_current_if_assigned()").use { it.next() && it.getObject(1) != null }
            }
        } catch (_: SQLException) {
            null
        }

    /** Runs a transaction command as text (`COMMIT`, `ROLLBACK`): JDBC's own refuse to work with auto-commit on. */
    fun runCommand(command: String) {
        session.createStatement().use { it.execute(command) }
    }

    private fun finishAutoTransaction(commit: Boolean) {
        try {
            if (commit) session.commit() else session.rollback()
        } finally {
            session.autoCommit = true
        }
    }

    private fun updateCount(statement: Statement): Long =
        try {
            statement.largeUpdateCount
        } catch (_: UnsupportedOperationException) {
            statement.updateCount.toLong()
        } catch (_: SQLFeatureNotSupportedException) {
            statement.updateCount.toLong()
        }
}

/** Runs cleanup whose failure changes nothing for the caller. */
internal inline fun quietly(action: () -> Unit) {
    try {
        action()
    } catch (_: Exception) {
        // Cleanup of a result or connection that may already be gone.
    }
}
