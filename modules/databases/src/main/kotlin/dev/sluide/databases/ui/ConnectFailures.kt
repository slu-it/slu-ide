package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.JdbcUrls
import java.sql.SQLException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** How a failed connect is shown and treated (ADR 0121). */
internal object ConnectFailures {
    private val connectionPrefix = Regex("""^\(conn=\d+\)\s*""")

    /** The shortest password that is masked in messages. */
    private const val MIN_MASKED = 4

    /**
     * The message of [error] for the card: the driver's message without MariaDB's `(conn=N)` prefix, the JDBC URL
     * replaced by the card's display form (a custom URL may carry credentials) and [password] masked (when it has at
     * least 4 characters). The password becomes a String only inside this call.
     */
    fun message(
        error: Throwable,
        config: ConnectionConfig,
        password: CharArray?,
    ): String {
        val cause = unwrapped(error)
        val raw = (cause.firstSql() ?: cause).message?.trim()?.ifEmpty { null } ?: cause.javaClass.simpleName
        var text = raw.replace(connectionPrefix, "")
        val url = JdbcUrls.url(config)
        if (url.isNotEmpty()) text = text.replace(url, JdbcUrls.display(config))
        // Shorter ones would mask ordinary words of the message and tell what the password is.
        if (password != null && password.size >= MIN_MASKED) text = text.replace(String(password), "********")
        return text
    }

    /**
     * Whether [error] is a failed login, so the cached password is forgotten: SQLState 28P01 or 28000 (PostgreSQL;
     * MariaDB's 28000), or MariaDB's error code 1045 ("Access denied").
     */
    fun isAuthFailure(
        error: Throwable,
        kind: DatabaseKind,
    ): Boolean =
        generateSequence(unwrapped(error)) { it.cause }.take(10).filterIsInstance<SQLException>().any {
            it.sqlState == "28P01" || it.sqlState == "28000" || (kind == DatabaseKind.MARIADB && it.errorCode == 1045)
        }

    /** The message of a failed commit, rollback or mode switch. */
    fun futureMessage(error: Throwable): String {
        val cause = unwrapped(error)
        return cause.message?.trim()?.ifEmpty { null } ?: cause.javaClass.simpleName
    }

    private fun unwrapped(error: Throwable): Throwable {
        val cause = error.cause
        return if ((error is CompletionException || error is ExecutionException) && cause != null) {
            unwrapped(cause)
        } else {
            error
        }
    }

    private fun Throwable.firstSql(): SQLException? =
        generateSequence(this) { it.cause }.take(10).filterIsInstance<SQLException>().firstOrNull()
}
