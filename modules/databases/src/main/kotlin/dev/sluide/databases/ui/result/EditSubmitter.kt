package dev.sluide.databases.ui.result

import dev.sluide.databases.result.BoundStatement
import java.sql.Connection
import java.sql.Savepoint

/** An UPDATE or DELETE that did not hit exactly one row: the data changed under the grid. */
internal class EditConflictException(
    matched: Int,
) : RuntimeException("The row was changed or deleted by someone else ($matched rows matched)")

/**
 * Runs the statements of a submit on the session connection (call it from `withSession(changes = true)`), all or
 * nothing. No JavaFX.
 */
internal object EditSubmitter {
    private const val SAVEPOINT = "db_edit"

    /**
     * With auto-commit on and no transaction of the user open ([autoCommitUserIdle]) the statements are one
     * transaction of their own (auto-commit off, commit, auto-commit on again). Otherwise they are wrapped in a
     * savepoint (SQL text with auto-commit on, JDBC's with it off), so the transaction of the user (Manual mode, or
     * BEGIN in Auto mode) survives a failure and stays open after a success. A failure rolls back to the state before and is rethrown.
     */
    fun submit(
        conn: Connection,
        statements: List<BoundStatement>,
        autoCommitUserIdle: Boolean,
    ) {
        if (conn.autoCommit && autoCommitUserIdle) ownTransaction(conn, statements) else savepoint(conn, statements)
    }

    private fun ownTransaction(
        conn: Connection,
        statements: List<BoundStatement>,
    ) {
        conn.autoCommit = false
        try {
            run(conn, statements)
            conn.commit()
        } catch (e: Throwable) {
            quietly(e) { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * With auto-commit on, JDBC savepoints are refused (a user's BEGIN keeps the transaction open, yet the driver
     * still thinks it is in auto-commit), so the savepoint is made with SQL text.
     */
    private fun savepoint(
        conn: Connection,
        statements: List<BoundStatement>,
    ) {
        if (conn.autoCommit) sqlSavepoint(conn, statements) else jdbcSavepoint(conn, statements)
    }

    private fun sqlSavepoint(
        conn: Connection,
        statements: List<BoundStatement>,
    ) {
        exec(conn, "SAVEPOINT $SAVEPOINT")
        try {
            run(conn, statements)
        } catch (e: Throwable) {
            quietly(e) { exec(conn, "ROLLBACK TO SAVEPOINT $SAVEPOINT") }
            throw e
        }
        quietly(null) { exec(conn, "RELEASE SAVEPOINT $SAVEPOINT") }
    }

    private fun exec(
        conn: Connection,
        sql: String,
    ) {
        conn.createStatement().use { it.execute(sql) }
    }

    private fun jdbcSavepoint(
        conn: Connection,
        statements: List<BoundStatement>,
    ) {
        val mark: Savepoint = conn.setSavepoint()
        try {
            run(conn, statements)
        } catch (e: Throwable) {
            quietly(e) { conn.rollback(mark) }
            throw e
        }
        quietly(null) { conn.releaseSavepoint(mark) }
    }

    private fun run(
        conn: Connection,
        statements: List<BoundStatement>,
    ) {
        for (statement in statements) {
            conn.prepareStatement(statement.sql).use { ps ->
                statement.params.forEachIndexed { i, value ->
                    val type = statement.types.getOrNull(i)
                    if (type != null) ps.setObject(i + 1, value, type) else ps.setObject(i + 1, value)
                }
                val count = ps.executeUpdate()
                if (statement.expectOneRow && count != 1) throw EditConflictException(count)
            }
        }
    }

    /** A failure of the clean-up is added to [primary]; without one it is dropped (the savepoint is not needed). */
    private inline fun quietly(
        primary: Throwable?,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (e: Exception) {
            primary?.addSuppressed(e)
        }
    }
}
