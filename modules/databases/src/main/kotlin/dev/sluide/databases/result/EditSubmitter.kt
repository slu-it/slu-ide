package dev.sluide.databases.result

import java.sql.Connection
import java.sql.SQLException
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
    private val log = System.getLogger(EditSubmitter::class.java.name)

    /**
     * With auto-commit on and no transaction of the user open ([autoCommitUserIdle]) the statements are one
     * transaction of their own (auto-commit off, commit, auto-commit on again). Otherwise they are wrapped in a
     * savepoint (SQL text with auto-commit on, JDBC's with it off), so the transaction of the user (Manual mode, or
     * BEGIN in Auto mode) survives a failure and stays open after a success. A failure rolls back to the state before
     * and is rethrown; a failure of that clean-up is added to it as suppressed. Once committed, the submit counts as
     * done: a failure to turn auto-commit on again is only logged (the session notices a broken connection on its
     * next statement). Every statement gets [timeoutSeconds] as its query timeout (0: none).
     */
    fun submit(
        conn: Connection,
        statements: List<BoundStatement>,
        autoCommitUserIdle: Boolean,
        timeoutSeconds: Int = 0,
    ) {
        val run = Run(conn, timeoutSeconds)
        if (conn.autoCommit && autoCommitUserIdle) run.ownTransaction(statements) else run.savepoint(statements)
    }

    private class Run(
        private val conn: Connection,
        private val timeoutSeconds: Int,
    ) {
        fun ownTransaction(statements: List<BoundStatement>) {
            conn.autoCommit = false
            try {
                run(statements)
                conn.commit()
            } catch (e: Throwable) {
                quietly(e) { conn.rollback() }
                quietly(e) { conn.autoCommit = true }
                throw e
            }
            try {
                conn.autoCommit = true
            } catch (e: SQLException) {
                log.log(System.Logger.Level.WARNING) {
                    "Auto-commit not restored after a committed edit submit (SQLState ${e.sqlState})"
                }
            }
        }

        /**
         * With auto-commit on, JDBC savepoints are refused (a user's BEGIN keeps the transaction open, yet the driver
         * still thinks it is in auto-commit), so the savepoint is made with SQL text.
         */
        fun savepoint(statements: List<BoundStatement>) {
            if (conn.autoCommit) sqlSavepoint(statements) else jdbcSavepoint(statements)
        }

        private fun sqlSavepoint(statements: List<BoundStatement>) {
            exec("SAVEPOINT $SAVEPOINT")
            try {
                run(statements)
            } catch (e: Throwable) {
                quietly(e) { exec("ROLLBACK TO SAVEPOINT $SAVEPOINT") }
                throw e
            }
            quietly(null) { exec("RELEASE SAVEPOINT $SAVEPOINT") }
        }

        private fun exec(sql: String) {
            conn.createStatement().use {
                if (timeoutSeconds > 0) it.queryTimeout = timeoutSeconds
                it.execute(sql)
            }
        }

        private fun jdbcSavepoint(statements: List<BoundStatement>) {
            val mark: Savepoint = conn.setSavepoint()
            try {
                run(statements)
            } catch (e: Throwable) {
                quietly(e) { conn.rollback(mark) }
                throw e
            }
            quietly(null) { conn.releaseSavepoint(mark) }
        }

        private fun run(statements: List<BoundStatement>) {
            for (statement in statements) {
                conn.prepareStatement(statement.sql).use { ps ->
                    if (timeoutSeconds > 0) ps.queryTimeout = timeoutSeconds
                    statement.params.forEachIndexed { i, value ->
                        val type = statement.types.getOrNull(i)
                        if (type != null) ps.setObject(i + 1, value, type) else ps.setObject(i + 1, value)
                    }
                    val count = ps.executeUpdate()
                    if (statement.expectOneRow && count != 1) throw EditConflictException(count)
                }
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
