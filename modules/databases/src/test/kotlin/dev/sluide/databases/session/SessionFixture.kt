package dev.sluide.databases.session

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * A [DbSession] over a [FakeDatabase] for unit tests: statements starting with `SELECT` are queries, [events] records
 * what the session reports. Close it after the test.
 */
class SessionFixture : AutoCloseable {
    val db = FakeDatabase()
    val events = CopyOnWriteArrayList<SessionEvent>()
    private val closedEvent = CountDownLatch(1)

    /** The statement timeout the session reads per statement. */
    @Volatile var timeout = 0

    /** The fetch-all cap the session reads per statement. */
    @Volatile var fetchAllCap = 100_000

    /** Cancels the session started, when opened with `captureCancels`; run them to let the cancel happen. */
    val cancels = CopyOnWriteArrayList<Runnable>()

    private var session: DbSession? = null

    fun open(
        mode: CommitMode = CommitMode.AUTO,
        kind: DatabaseKind = DatabaseKind.POSTGRESQL,
        readOnly: Boolean = false,
        captureCancels: Boolean = false,
        closeGraceMillis: Long = 2_000,
        password: CharArray? = null,
    ): DbSession {
        db.exposesTransactionState = kind == DatabaseKind.POSTGRESQL
        val connections = ArrayDeque(listOf(db.session, db.metadata))
        val config = ConnectionConfig("id", "local", kind, commitMode = mode, readOnly = readOnly)
        val startCancel: ((Runnable) -> Unit)? = if (captureCancels) cancels::add else null
        return DbSessions
            .open(
                config,
                password,
                { _, given -> connections.removeFirstOrNull() ?: db.reconnect(given) },
                { it.startsWith("SELECT") },
                { timeout },
                { fetchAllCap },
                startCancel,
                closeGraceMillis,
            ).also {
                it.addListener { event ->
                    events += event
                    if (event is SessionEvent.Closed) closedEvent.countDown()
                }
                session = it
            }
    }

    /** Waits for the session's Closed event. */
    fun awaitClosed() = assertTrue(closedEvent.await(10, TimeUnit.SECONDS), "Closed was not emitted")

    /** Waits until [condition] holds (a poll with a deadline, for work the session does without telling). */
    fun eventually(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "never: $message" }
            Thread.onSpinWait()
        }
    }

    fun transactionEvents() = events.filterIsInstance<SessionEvent.TransactionChanged>().map { it.pending }

    override fun close() {
        session?.close()
    }
}

fun <T> CompletableFuture<T>.await(): T = get(10, TimeUnit.SECONDS)

/** The first column of each row of the page. */
fun Page.firsts() = rows.map { it.first() }
