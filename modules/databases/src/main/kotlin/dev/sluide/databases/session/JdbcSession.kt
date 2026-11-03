package dev.sluide.databases.session

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = System.getLogger(JdbcSession::class.java.name)

/**
 * [DbSession] over two JDBC connections. Each is used for its work only on its own [ConnectionWorker] thread; other
 * threads only cancel a running statement (`Statement.cancel`) and, at a close that outlasts the grace period, abort a
 * connection. Logs name the session by a process-wide number (`db#3`) and the database kind, never the URL, user,
 * password or SQL (ADR 0060). The pending flag and transaction ends: [SessionTransactions]; starting and closing
 * statements: [SessionStatements].
 */
internal class JdbcSession(
    override val config: ConnectionConfig,
    private val session: Connection,
    /** Replaced by [reconnect] when it was lost; metadata thread only (and an abort at close). */
    @Volatile private var metadata: Connection,
    private val isQuery: (String) -> Boolean,
    private val limits: SessionLimits,
    private val reconnect: MetadataReconnect,
    private val number: Int,
    /** Starts the cancel of a statement off the caller's thread: it opens a connection to the server. */
    private val startCancel: (Runnable) -> Unit,
) : DbSession,
    CursorHost {
    private val closing = AtomicBoolean()
    private val sessionWorker = ConnectionWorker("sluide-db-session-$number") { closing.get() }
    private val metadataWorker = ConnectionWorker("sluide-db-metadata-$number") { closing.get() }
    private val listeners = SessionListeners(number)
    private val current = AtomicReference<Run?>()
    private val cancelLock = ReentrantLock()

    /** Runs the end of a close (aborts, the Closed event) on threads of its own, never the caller's. */
    private val closer = Executor { NamedThreads.virtual("sluide-db-close-$number", it) }
    private val statements = SessionStatements(session, config.kind, number, ::beginRun) { finishRun() }
    private val timeoutSeconds = limits.statementTimeoutSeconds

    /** The cursor whose result set is open; session thread only. */
    private var liveCursor: CursorState? = null

    @Volatile override var commitMode = config.commitMode
        private set

    @Volatile override var pendingTransaction = false
        private set

    @Volatile override var running = false
        private set

    override val closed get() = closing.get()

    override val statementTimeoutSeconds: Int get() = timeoutSeconds()

    private val transactions =
        SessionTransactions(
            session,
            config.kind,
            statements,
            { commitMode },
            { pendingTransaction },
            ::setPending,
        ) { liveCursor?.live?.autoTransaction == true }

    /** A statement that runs on the session thread and can be cancelled. */
    private class Run(
        val statement: Statement,
    ) {
        @Volatile var cancelRequested = false
    }

    override fun execute(
        sql: String,
        pageSize: Int,
    ): CompletableFuture<StatementOutcome> {
        require(pageSize > 0) { "Page size must be positive: $pageSize" }
        return onSessionThread { runStatement(sql, pageSize) }
    }

    override fun cancel() {
        val run = current.get() ?: return
        run.cancelRequested = true
        startCancel {
            // Holding the lock keeps the session thread from starting its next statement before the cancel arrived,
            // so a late cancel cannot hit that one (MariaDB cancels whatever the connection runs).
            cancelLock.withLock {
                if (current.get() === run) {
                    try {
                        run.statement.cancel()
                    } catch (e: SQLException) {
                        // A statement that failed meanwhile is closed before its run ends; its cancel fails harmlessly.
                        if (!runCatching { run.statement.isClosed }.getOrDefault(true)) {
                            log.log(System.Logger.Level.WARNING, "db#$number cancel failed (SQLState ${e.sqlState})")
                        }
                    }
                }
            }
        }
    }

    override fun setCommitMode(mode: CommitMode): CompletableFuture<Unit> =
        onSessionThread {
            releaseLive()
            if (mode != commitMode) {
                transactions.switchTo(mode)
                commitMode = mode
            }
        }

    override fun commit(): CompletableFuture<Unit> = endTransaction(commit = true)

    override fun rollback(): CompletableFuture<Unit> = endTransaction(commit = false)

    private fun endTransaction(commit: Boolean): CompletableFuture<Unit> =
        onSessionThread {
            releaseLive()
            transactions.end(commit)
        }

    override fun <T> withSession(
        changes: Boolean,
        work: (Connection) -> T,
    ): CompletableFuture<T> =
        onSessionThread {
            releaseLive()
            try {
                work(session).also { transactions.succeeded(null, textRule = changes) }
            } catch (e: Exception) {
                transactions.failed(null, textRule = changes)
                throw e
            }
        }

    override fun <T> withMetadata(work: (Connection) -> T): CompletableFuture<T> =
        metadataWorker.submit {
            try {
                work(metadata)
            } catch (e: SQLException) {
                if (!metadataLost(e)) throw e
                log.log(System.Logger.Level.WARNING) { "db#$number metadata connection lost, reconnecting" }
                quietly { metadata.close() }
                metadata = reconnect.open(config)
                log.log(System.Logger.Level.INFO) { "db#$number metadata connection reopened" }
                work(metadata)
            }
        }

    /** The metadata connection is gone (SQLState class 08, or closed); the session connection is not affected. */
    private fun metadataLost(error: SQLException) =
        SqlFailures.isConnectionLost(error) || runCatching { metadata.isClosed }.getOrDefault(true)

    override fun addListener(listener: (SessionEvent) -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: (SessionEvent) -> Unit) {
        listeners.remove(listener)
    }

    override fun close() = shutDown(null)

    override fun <T> onSession(work: () -> T): CompletableFuture<T> = onSessionThread(work)

    /** Session-thread work; an SQLException also checks whether the connection broke. */
    private fun <T> onSessionThread(work: () -> T): CompletableFuture<T> =
        sessionWorker.submit {
            try {
                work()
            } catch (e: SQLException) {
                checkConnection(session, e)
                throw e
            } finally {
                finishRun()
            }
        }

    private fun runStatement(
        sql: String,
        pageSize: Int,
    ): StatementOutcome {
        releaseLive()
        val started = System.nanoTime()
        val query = isQuery(sql)
        val timeout = timeoutSeconds()
        val outcome =
            try {
                when (val result = start(sql, query, pageSize, timeout)) {
                    is SessionStatements.Started.Count -> {
                        transactions.succeeded(sql, textRule = !query)
                        StatementOutcome.Updated(sql, millisSince(started), result.count)
                    }

                    is SessionStatements.Started.Result -> {
                        val cursor = cursorOf(sql, query, pageSize, result.live)
                        val first = cursor.read(0, pageSize)
                        transactions.succeeded(sql, textRule = !query)
                        val view = CursorView(this, cursor, pageSize)
                        StatementOutcome.Rows(sql, millisSince(started), cursor.columns, view, first)
                    }
                }
            } catch (e: Exception) {
                val cancelled = finishRun()?.cancelRequested == true
                transactions.failed(sql, textRule = !query)
                if (e is SQLException) {
                    checkConnection(session, e)
                } else {
                    log.log(System.Logger.Level.ERROR, "db#$number statement failed", e)
                }
                SqlFailures.outcome(sql, millisSince(started), e, cancelled, timeout)
            }
        log.log(
            System.Logger.Level.INFO,
        ) { "db#$number statement finished in ${outcome.millis} ms: ${summary(outcome)}" }
        return outcome
    }

    private fun cursorOf(
        sql: String,
        query: Boolean,
        pageSize: Int,
        live: LiveResult,
    ): CursorState {
        val cursor =
            try {
                CursorState(this, sql, JdbcValues.columns(live.resultSet), pageSize, query, live)
            } catch (e: Exception) {
                end(live, reachedEnd = false, failed = true)
                throw e
            }
        liveCursor = cursor
        return cursor
    }

    override fun reopen(cursor: CursorState): LiveResult {
        if (liveCursor !== cursor) releaseLive()
        liveCursor = cursor
        val started = start(cursor.sql, cursor.query, cursor.fetchSize, timeoutSeconds())
        check(started is SessionStatements.Started.Result) { "The statement gave no rows when run again" }
        return started.live
    }

    private fun start(
        sql: String,
        query: Boolean,
        fetchSize: Int,
        timeout: Int,
    ) = statements.start(sql, transactions.ownTransactionFor(query), fetchSize, timeout, rowLimit())

    /**
     * Inside a transaction the user opened in AUTO mode PostgreSQL reads a result into memory (no cursor without
     * turning auto-commit off), so it reads at most the fetch-all cap plus one row, which tells that there were more.
     */
    private fun rowLimit(): Int? =
        limits.fetchAllCap().takeIf {
            config.kind == DatabaseKind.POSTGRESQL &&
                commitMode == CommitMode.AUTO &&
                statements.serverInTransaction() == true
        }

    override fun end(
        live: LiveResult,
        reachedEnd: Boolean,
        failed: Boolean,
    ) = statements.end(live, reachedEnd, failed)

    private fun releaseLive() {
        liveCursor?.release()
        liveCursor = null
    }

    private fun beginRun(statement: Statement) {
        current.set(Run(statement))
        setRunning(true)
    }

    /** Ends the running statement, if any: a cancel can no longer reach it. */
    private fun finishRun(): Run? {
        val run = cancelLock.withLock { current.getAndSet(null) } ?: return null
        setRunning(false)
        return run
    }

    private fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        listeners.emit(SessionEvent.RunningChanged(value))
    }

    private fun setPending(value: Boolean) {
        if (pendingTransaction == value) return
        pendingTransaction = value
        listeners.emit(SessionEvent.TransactionChanged(value))
    }

    /** After [error] on [connection]: a lost connection (SQLState class 08, or no longer valid) closes the session. */
    private fun checkConnection(
        connection: Connection,
        error: SQLException,
    ) {
        if (closing.get()) return
        val lost =
            SqlFailures.isConnectionLost(error) ||
                !(runCatching { connection.isValid(VALID_SECONDS) }.getOrDefault(false))
        if (lost) shutDown(error.message?.trim()?.ifEmpty { null } ?: "Connection lost (SQLState ${error.sqlState})")
    }

    /**
     * Closes the session once: cancels the running statement; on the session thread closes the open result, rolls
     * back and closes the connection, on the metadata thread closes that one; then emits Closed. A broken connection
     * ([error]) is closed without talking to the server first: no cancel, no rollback. Work that does not end within
     * the grace period (an edit waiting for a row lock) has its connection aborted and its future failed.
     */
    private fun shutDown(error: String?) {
        if (!closing.compareAndSet(false, true)) return
        reconnect.forget()
        if (error == null) {
            log.log(System.Logger.Level.INFO) { "db#$number closing (${config.kind.label})" }
            cancel()
        } else {
            log.log(System.Logger.Level.WARNING) { "db#$number connection lost, closing (${config.kind.label})" }
        }
        val sessionClosed =
            sessionWorker.finish {
                if (error == null) {
                    quietly { releaseLive() }
                    quietly { transactions.end(commit = false) }
                } else {
                    liveCursor?.abandon()
                }
                liveCursor = null
                quietly { session.close() }
                setPending(false)
            }
        val metadataClosed = metadataWorker.finish { quietly { metadata.close() } }
        CompletableFuture
            .allOf(
                withinGrace(sessionWorker, session, sessionClosed),
                withinGrace(metadataWorker, metadata, metadataClosed),
            ).whenCompleteAsync({ _, _ -> listeners.emit(SessionEvent.Closed(error)) }, closer)
    }

    /** Completes when [closed] does, or after the grace period by aborting [connection] and failing the worker's work. */
    private fun withinGrace(
        worker: ConnectionWorker,
        connection: Connection,
        closed: CompletableFuture<Unit>,
    ): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        closed.whenComplete { _, _ -> done.complete(Unit) }
        CompletableFuture.delayedExecutor(limits.closeGraceMillis, TimeUnit.MILLISECONDS, closer).execute {
            if (!done.isDone) {
                log.log(
                    System.Logger.Level.WARNING,
                ) { "db#$number work did not end within ${limits.closeGraceMillis} ms, aborting" }
                quietly { connection.abort(closer) }
                worker.abandon()
                done.complete(Unit)
            }
        }
        return done
    }

    companion object {
        /** Sets up a metadata connection: auto-commit on, read-only. */
        fun setUpMetadata(connection: Connection) {
            connection.autoCommit = true
            connection.isReadOnly = true
        }

        private const val VALID_SECONDS = 5

        private fun millisSince(started: Long) = (System.nanoTime() - started) / 1_000_000

        private fun summary(outcome: StatementOutcome) =
            when (outcome) {
                is StatementOutcome.Rows -> "rows"
                is StatementOutcome.Updated -> "updated ${outcome.count}"
                is StatementOutcome.Failed -> "failed (SQLState ${outcome.sqlState})"
                is StatementOutcome.Cancelled -> "cancelled"
            }
    }
}
