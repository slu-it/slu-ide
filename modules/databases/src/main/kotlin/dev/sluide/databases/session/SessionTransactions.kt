package dev.sluide.databases.session

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.DatabaseKind
import java.sql.Connection

/**
 * The pending-transaction flag of a session and the ends of its transactions (ADR 0121); session thread only.
 *
 * - MANUAL mode: on PostgreSQL the server says whether the transaction wrote ([SessionStatements.serverWrote]), which
 *   also sees writes of functions in a SELECT and a COMMIT typed into the console; only when it cannot tell does the
 *   text rule (not a query, or work with changes) mark it. On MariaDB the text rule alone: a statement that is not a
 *   query marks it, a typed COMMIT or ROLLBACK clears it.
 * - AUTO mode: a transaction the user opened (BEGIN) is pending too, so that disconnecting asks before it is lost: the
 *   server's transaction state ([SessionStatements.serverInTransaction]), not counting the session's own transaction
 *   of an open PostgreSQL result; else the text of BEGIN / START TRANSACTION and COMMIT / ROLLBACK.
 */
internal class SessionTransactions(
    private val session: Connection,
    private val kind: DatabaseKind,
    private val statements: SessionStatements,
    private val mode: () -> CommitMode,
    private val pending: () -> Boolean,
    private val setPending: (Boolean) -> Unit,
    /** Whether the open result runs in the session's own AUTO transaction. */
    private val ownTransactionOpen: () -> Boolean,
) {
    /** Whether a query in AUTO mode gets a transaction of its own (see [SessionStatements.start]). */
    fun ownTransactionFor(query: Boolean): Boolean =
        mode() == CommitMode.AUTO &&
            query &&
            kind == DatabaseKind.POSTGRESQL &&
            statements.serverInTransaction() == false

    /** After [sql] (null for `withSession` work) succeeded; [textRule]: not a query, or work with changes. */
    fun succeeded(
        sql: String?,
        textRule: Boolean,
    ) {
        when {
            mode() == CommitMode.AUTO -> followServer(sql)
            kind == DatabaseKind.POSTGRESQL -> setPending(statements.serverWrote() ?: (pending() || textRule))
            else -> setPending(sql?.let(::transactionText) ?: (pending() || textRule))
        }
    }

    /**
     * After [sql] (null for work) failed. PostgreSQL has aborted a MANUAL transaction and refuses every further
     * statement: with nothing written before (not pending, as the server reported) it is rolled back at once, losing
     * nothing; else it stays pending and the user rolls back. MariaDB keeps the transaction; [textRule] marks it.
     */
    fun failed(
        sql: String?,
        textRule: Boolean,
    ) {
        when {
            mode() == CommitMode.AUTO -> followServer(sql)
            kind == DatabaseKind.POSTGRESQL -> if (!pending()) quietly { session.rollback() }
            textRule -> setPending(true)
        }
    }

    /**
     * Commits or rolls back: through JDBC with auto-commit off, else (AUTO mode) a transaction the user opened, as a
     * `COMMIT` / `ROLLBACK` statement.
     */
    fun end(commit: Boolean) {
        if (!session.autoCommit) {
            if (commit) session.commit() else session.rollback()
        } else if (pending() || statements.serverInTransaction() == true) {
            statements.runCommand(if (commit) "COMMIT" else "ROLLBACK")
        }
        setPending(false)
    }

    /** Switches the connection to [target]; leaving MANUAL mode commits (JDBC), entering it keeps an open BEGIN. */
    fun switchTo(target: CommitMode) {
        session.autoCommit = target == CommitMode.AUTO
        if (target == CommitMode.AUTO) setPending(false) else succeeded(null, textRule = false)
    }

    private fun followServer(sql: String?) {
        val open = if (ownTransactionOpen()) false else statements.serverInTransaction()
        setPending(open ?: sql?.let(::transactionText) ?: pending())
    }

    companion object {
        private val begins = Regex("""^\s*(BEGIN|START\s+TRANSACTION)\b""", RegexOption.IGNORE_CASE)
        private val ends = Regex("""^\s*(COMMIT|END|ROLLBACK(?!\s+(WORK\s+)?TO\b))\b""", RegexOption.IGNORE_CASE)

        /** True when [sql] opens a transaction, false when it ends one, else null. */
        internal fun transactionText(sql: String): Boolean? =
            when {
                begins.containsMatchIn(sql) -> true
                ends.containsMatchIn(sql) -> false
                else -> null
            }
    }
}
