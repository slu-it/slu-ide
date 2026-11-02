package dev.sluide.databases.session

import org.postgresql.util.PSQLException
import java.sql.SQLException
import java.sql.SQLTimeoutException

/** How a failed statement is reported (ADR 0121). */
internal object SqlFailures {
    /**
     * The outcome of [sql] that threw [error]: Cancelled when the user cancelled it, else Failed with the driver's
     * message, SQLState and PostgreSQL's error position. A statement stopped by the statement timeout (both drivers
     * report it as a cancel: PostgreSQL 57014, MariaDB 70100 "Query execution was interrupted (max_statement_time
     * exceeded)") is Failed and says so.
     */
    fun outcome(
        sql: String,
        millis: Long,
        error: Throwable,
        userCancelled: Boolean,
        timeoutSeconds: Int,
    ): StatementOutcome {
        if (userCancelled) return StatementOutcome.Cancelled(sql, millis)
        val sqlState = (error as? SQLException)?.sqlState
        val message = error.message?.trim()?.ifEmpty { null } ?: error.javaClass.simpleName
        if (timeoutSeconds > 0 && (error is SQLTimeoutException || isCancel(error))) {
            val timedOut = "Statement timeout of $timeoutSeconds s exceeded: $message"
            return StatementOutcome.Failed(sql, millis, timedOut, sqlState, null)
        }
        if (isCancel(error)) return StatementOutcome.Cancelled(sql, millis)
        return StatementOutcome.Failed(sql, millis, message, sqlState, position(error, sql))
    }

    /** Whether [error] is the server's report of a cancelled statement (also by another session or a timeout). */
    fun isCancel(error: Throwable): Boolean {
        val sqlState = (error as? SQLException)?.sqlState
        return sqlState == "57014" || sqlState == "70100" ||
            error.message?.contains("Query execution was interrupted") == true
    }

    /** Whether [error] says the connection is gone: SQLState class 08 (connection exception). */
    fun isConnectionLost(error: Throwable): Boolean = (error as? SQLException)?.sqlState?.startsWith("08") == true

    /** PostgreSQL's 1-based error position (in characters, i.e. code points) as a 0-based offset into [sql]. */
    internal fun position(
        error: Throwable,
        sql: String,
    ): Int? {
        val position = (error as? PSQLException)?.serverErrorMessage?.position ?: return null
        if (position <= 0 || position - 1 > sql.codePointCount(0, sql.length)) return null
        return sql.offsetByCodePoints(0, position - 1)
    }
}
