package dev.sluide.databases.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.SessionEvent
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private const val RUNNING_REASON = "A statement is running."
private const val EDITS_REASON = "There are unsubmitted edits."

/**
 * The sessions of the [entries] of a [DatabasesTabModel] (ADR 0121): connecting as a cancellable background task,
 * session events mirrored to the entries, disconnecting with its questions, commit, rollback and the commit mode.
 * Nothing here logs a password, a URL or a user. FX thread; results of other threads come back through [fx].
 */
internal class EntrySessions(
    private val passwords: PasswordCache,
    private val tasks: BackgroundTasks,
    private val dialogs: DatabaseDialogs,
    private val openSession: (ConnectionConfig, CharArray?) -> DbSession,
    private val startWorker: (String, Runnable) -> Unit,
    private val fx: (Runnable) -> Unit,
    /** The project folder the connections belong to, the password cache's key. */
    private val folder: () -> Path?,
    /** The model's cards; a session whose card is gone is closed. */
    private val entries: List<ConnectionEntry>,
) {
    fun connect(entry: ConnectionEntry) {
        val status = entry.status.get()
        if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED) return
        val config = entry.config.get()
        val folder = folder()
        val password =
            passwords.get(folder, config.id)
                ?: dialogs.askPassword(config)?.also { passwords.put(folder, config.id, it) }
                ?: return
        lateinit var attempt: ConnectAttempt
        attempt = ConnectAttempt(tasks.start("Connecting to ${config.name}") { cancelConnect(entry, attempt) })
        entry.attempt = attempt
        entry.connecting()
        startWorker("sluide-db-connect") {
            val used = password.takeIf { it.isNotEmpty() }
            try {
                val session = openSession(config, used)
                fx { arrived(entry, attempt, session) }
            } catch (e: Throwable) {
                val message = ConnectFailures.message(e, config, used)
                val auth = ConnectFailures.isAuthFailure(e, config.kind)
                fx { failed(entry, attempt, folder, config, message, auth) }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun arrived(
        entry: ConnectionEntry,
        attempt: ConnectAttempt,
        session: DbSession,
    ) {
        if (attempt.cancelled || entry.attempt !== attempt || entry !in entries) {
            session.close()
            attempt.task.failed("cancelled")
            return
        }
        attempt.task.close()
        entry.attempt = null
        val listener: (SessionEvent) -> Unit = { event -> fx { event(entry, session, event) } }
        entry.listener = listener
        session.addListener(listener)
        entry.connected(session)
        // A session that broke before the listener was added sends no Closed to it.
        if (session.closed) entry.ended("The connection was closed.")
    }

    private fun failed(
        entry: ConnectionEntry,
        attempt: ConnectAttempt,
        folder: Path?,
        config: ConnectionConfig,
        message: String,
        authFailure: Boolean,
    ) {
        if (authFailure) passwords.forget(folder, config.id)
        attempt.task.failed("could not connect")
        if (attempt.cancelled || entry.attempt !== attempt) return
        entry.attempt = null
        entry.ended(message)
    }

    private fun cancelConnect(
        entry: ConnectionEntry,
        attempt: ConnectAttempt,
    ) {
        if (attempt.cancelled) return
        attempt.cancelled = true
        if (entry.attempt === attempt) {
            entry.attempt = null
            entry.ended(null)
        }
    }

    /** A session event, on the FX thread; events of an older session are ignored. */
    private fun event(
        entry: ConnectionEntry,
        session: DbSession,
        event: SessionEvent,
    ) {
        if (entry.session.get() !== session) return
        when (event) {
            is SessionEvent.RunningChanged -> entry.setRunning(event.running)
            is SessionEvent.TransactionChanged -> entry.setPending(event.pending)
            is SessionEvent.Closed -> entry.ended(event.error)
        }
    }

    /**
     * Disconnects [entry] after its questions; `false` when the user cancelled, or while an earlier disconnect still
     * ends its transaction (so a second click asks nothing). A connect in progress is cancelled. [then] runs once the
     * session is closed: at once, or after the chosen Commit or Roll Back ended. A failed Commit keeps the connection
     * open and says why; while the entry is being removed ([removing]) it says the transaction was rolled back, and the
     * removal goes on.
     */
    fun disconnect(
        entry: ConnectionEntry,
        removing: Boolean,
        then: () -> Unit = {},
    ): Boolean {
        if (entry.ending) return false
        entry.attempt?.let { attempt ->
            stop(entry, attempt)
            then()
            return true
        }
        val session = entry.session.get()
        if (session == null) {
            then()
            return true
        }
        val name = entry.config.get().name
        val end =
            if (session.pendingTransaction) {
                dialogs.askTransactionEnd(name) ?: return false
            } else {
                null
            }
        val reasons = reasons(entry, session)
        if (reasons.isNotEmpty() && !dialogs.confirmDisconnect(name, reasons)) return false
        if (end == null) {
            close(entry)
            then()
            return true
        }
        if (session.running) session.cancel()
        entry.ending = true
        val ending = if (end == TransactionEnd.COMMIT) session.commit() else session.rollback()
        ending.whenComplete { _, error ->
            fx { ended(entry, session, end, error, removing, then) }
        }
        return true
    }

    /** A Commit or Roll Back chosen before a disconnect has ended, with [error] if it failed. */
    private fun ended(
        entry: ConnectionEntry,
        session: DbSession,
        end: TransactionEnd,
        error: Throwable?,
        removing: Boolean,
        then: () -> Unit,
    ) {
        entry.ending = false
        val name = entry.config.get().name
        val commitFailed = error != null && end == TransactionEnd.COMMIT
        when {
            // Closed meanwhile: the window closes, the folder changed or the connection broke.
            entry.session.get() !== session -> {
                session.close()
                then()
            }

            commitFailed && !removing -> {
                // A session that broke meanwhile ends with its Closed event, which the card shows.
                if (!session.closed) {
                    val message = ConnectFailures.futureMessage(checkNotNull(error))
                    dialogs.showError("Commit failed", "$name stays connected: $message")
                }
            }

            else -> {
                if (commitFailed) {
                    val message = ConnectFailures.futureMessage(checkNotNull(error))
                    dialogs.showError(
                        "Commit failed",
                        "Commit failed; the transaction was rolled back.\n$name: $message",
                    )
                }
                close(entry, session)
                then()
            }
        }
    }

    /**
     * The window closes: every session is closed, every connect cancelled, without a question. A disconnect still
     * ending its transaction then finds its session gone and says nothing.
     */
    fun closeAll() = entries.forEach(::close)

    /** Closes the session of [entry] without a question (it rolls back), or cancels its connect. */
    fun close(entry: ConnectionEntry) {
        entry.attempt?.let { stop(entry, it) }
        entry.session.get()?.let { close(entry, it) }
    }

    /** Cancels the connect [attempt] through its task, so that its end is logged as cancelled. */
    private fun stop(
        entry: ConnectionEntry,
        attempt: ConnectAttempt,
    ) {
        attempt.task.cancel()
        cancelConnect(entry, attempt)
    }

    private fun close(
        entry: ConnectionEntry,
        session: DbSession,
    ) {
        if (entry.session.get() === session) {
            entry.listener?.let(session::removeListener)
            entry.ended(null)
        }
        session.close()
    }

    /**
     * What closing the session of [entry] loses: "<name>: uncommitted transaction (rolled back), statement running
     * (cancelled), unsubmitted edits (lost)", or `null` if nothing.
     */
    fun closeWarning(entry: ConnectionEntry): String? {
        val session = entry.session.get() ?: return null
        val parts =
            listOfNotNull(
                "uncommitted transaction (rolled back)".takeIf { session.pendingTransaction },
                "statement running (cancelled)".takeIf { session.running },
                "unsubmitted edits (lost)".takeIf { entry.unsubmittedEdits() },
            )
        return if (parts.isEmpty()) null else "${entry.config.get().name}: ${parts.joinToString(", ")}"
    }

    fun setCommitMode(
        entry: ConnectionEntry,
        mode: CommitMode,
    ) {
        val session = entry.session.get() ?: return
        if (session.commitMode == mode) return
        val name = entry.config.get().name
        val switched =
            if (session.commitMode == CommitMode.MANUAL && mode == CommitMode.AUTO && session.pendingTransaction) {
                when (dialogs.askTransactionEnd(name) ?: return) {
                    TransactionEnd.COMMIT -> session.commit()
                    TransactionEnd.ROLLBACK -> session.rollback()
                }.thenCompose { session.setCommitMode(mode) }
            } else {
                session.setCommitMode(mode)
            }
        after(entry, session, switched, "Commit mode not changed")
    }

    fun commit(
        entry: ConnectionEntry,
        onDone: ((Throwable?) -> Boolean)? = null,
    ) {
        val session = entry.session.get() ?: return
        after(entry, session, session.commit(), "Commit failed", onDone)
    }

    fun rollback(
        entry: ConnectionEntry,
        onDone: ((Throwable?) -> Boolean)? = null,
    ) {
        val session = entry.session.get() ?: return
        after(entry, session, session.rollback(), "Roll back failed", onDone)
    }

    /**
     * Once [work] is done: the entry shows the session's commit mode; a failure is shown with [title] unless [onDone]
     * handled it: it gets the failure (or `null`) and returns whether it reported it itself. [onDone] is not called
     * when the entry has another session by then.
     */
    private fun after(
        entry: ConnectionEntry,
        session: DbSession,
        work: CompletableFuture<Unit>,
        title: String,
        onDone: ((Throwable?) -> Boolean)? = null,
    ) {
        work.whenComplete { _, error ->
            fx {
                if (entry.session.get() !== session) return@fx
                entry.setCommitMode(session.commitMode)
                val handled = onDone?.invoke(error) ?: false
                if (!handled && error != null && !session.closed) {
                    dialogs.showError(title, "${entry.config.get().name}: ${ConnectFailures.futureMessage(error)}")
                }
            }
        }
    }

    private fun reasons(
        entry: ConnectionEntry,
        session: DbSession,
    ): List<String> =
        listOfNotNull(
            RUNNING_REASON.takeIf { session.running },
            EDITS_REASON.takeIf { entry.unsubmittedEdits() },
        )
}
