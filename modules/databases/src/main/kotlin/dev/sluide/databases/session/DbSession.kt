package dev.sluide.databases.session

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * The JDBC connections of one connected card (ADR 0121): the *session* connection runs statements and holds the
 * transaction, the *metadata* connection serves the tree, completion and DDL. Each has its own thread; nothing here
 * touches JDBC on the caller's thread.
 */
interface DbSession : AutoCloseable {
    val config: ConnectionConfig
    val commitMode: CommitMode
    val pendingTransaction: Boolean
    val running: Boolean
    val closed: Boolean

    /** Switching MANUAL→AUTO commits a pending transaction (JDBC semantics); the UI asks before. */
    fun setCommitMode(mode: CommitMode): CompletableFuture<Unit>

    /**
     * Runs [sql] after any work queued before it. SQL errors complete normally as Failed; completes exceptionally
     * only when the session is closed.
     */
    fun execute(
        sql: String,
        pageSize: Int,
    ): CompletableFuture<StatementOutcome>

    /** Cancels the running statement (Statement.cancel), from any thread; no-op when idle. */
    fun cancel()

    fun commit(): CompletableFuture<Unit>

    fun rollback(): CompletableFuture<Unit>

    /**
     * Runs work on the session connection's thread (edit submits). changes=true marks the transaction pending in MANUAL
     * mode. While the user has a transaction open in AUTO mode ([pendingTransaction]), work must not toggle
     * auto-commit: turning it on commits that transaction.
     */
    fun <T> withSession(
        changes: Boolean = false,
        work: (Connection) -> T,
    ): CompletableFuture<T>

    /**
     * Runs work on the metadata connection's thread (tree, completion, DDL). When the metadata connection is lost
     * (SQLState class 08, or closed), it is opened again and the work retried once; the session stays open either way.
     */
    fun <T> withMetadata(work: (Connection) -> T): CompletableFuture<T>

    /** Listeners are called on worker threads; Closed is the last event a listener gets. */
    fun addListener(listener: (SessionEvent) -> Unit)

    fun removeListener(listener: (SessionEvent) -> Unit)

    /**
     * Idempotent, any thread, does not block the caller for long: cancels a running statement, rolls back, closes
     * both connections (on their threads). Work queued before completes exceptionally with [DbSessionClosedException];
     * work still running after a grace period (2 s) has its connection aborted and its future failed the same way.
     * Closed is emitted on a thread of the session, never the caller's.
     */
    override fun close()
}

/** What a session reports to its listeners. */
sealed interface SessionEvent {
    data class RunningChanged(
        val running: Boolean,
    ) : SessionEvent

    data class TransactionChanged(
        val pending: Boolean,
    ) : SessionEvent

    /** The session closed: by close() (error null) or because the connection broke (message). */
    data class Closed(
        val error: String?,
    ) : SessionEvent
}

/** Work given to a session that is closed, or was queued when it closed. */
class DbSessionClosedException(
    message: String = "The database session is closed",
) : IllegalStateException(message)

/** Opens [DbSession]s. */
object DbSessions {
    private val log = System.getLogger(DbSessions::class.java.name)
    private val numbers = AtomicInteger()

    /**
     * Opens both connections (blocking, never on the FX thread); throws SQLException on failure (both closed).
     * [connect] opens one connection (the caller passes DriverManager with `JdbcUrls`); [isQuery] tells a plain query
     * for [config]'s kind (`{ SqlSplitter.isQuery(it, kind) }`); [statementTimeoutSeconds] is read per statement, 0 for
     * none; [fetchAllCap] (the fetch-all cap setting) is read per statement and limits the rows PostgreSQL reads into
     * memory for a statement inside a transaction the user opened in AUTO mode. The session keeps a copy of
     * [password] to open the metadata connection again; the copy is zeroed when the session closes.
     */
    fun open(
        config: ConnectionConfig,
        password: CharArray?,
        connect: (ConnectionConfig, CharArray?) -> Connection,
        isQuery: (String) -> Boolean,
        statementTimeoutSeconds: () -> Int,
        fetchAllCap: () -> Int,
    ): DbSession = open(config, password, connect, isQuery, statementTimeoutSeconds, fetchAllCap, null, 2_000)

    /** [open] with the seams of tests: [startCancel] runs a cancel (default: a virtual thread), the close grace. */
    internal fun open(
        config: ConnectionConfig,
        password: CharArray?,
        connect: (ConnectionConfig, CharArray?) -> Connection,
        isQuery: (String) -> Boolean,
        statementTimeoutSeconds: () -> Int,
        fetchAllCap: () -> Int,
        startCancel: ((Runnable) -> Unit)?,
        closeGraceMillis: Long,
    ): DbSession {
        val started = System.nanoTime()
        val session = connect(config, password)
        val metadata =
            try {
                connect(config, password)
            } catch (e: Throwable) {
                quietly { session.close() }
                throw e
            }
        try {
            if (config.readOnly) session.isReadOnly = true
            session.autoCommit = config.commitMode == CommitMode.AUTO
            JdbcSession.setUpMetadata(metadata)
        } catch (e: Throwable) {
            quietly { session.close() }
            quietly { metadata.close() }
            throw e
        }
        val number = numbers.incrementAndGet()
        val cancels: (Runnable) -> Unit = startCancel ?: { NamedThreads.virtual("sluide-db-cancel-$number", it) }
        val millis = (System.nanoTime() - started) / 1_000_000
        log.log(System.Logger.Level.INFO) { "db#$number connected (${config.kind.label}) in $millis ms" }
        val limits = SessionLimits(statementTimeoutSeconds, fetchAllCap, closeGraceMillis)
        val reconnect = MetadataReconnect(connect, password?.copyOf())
        return JdbcSession(config, session, metadata, isQuery, limits, reconnect, number, cancels)
    }
}
