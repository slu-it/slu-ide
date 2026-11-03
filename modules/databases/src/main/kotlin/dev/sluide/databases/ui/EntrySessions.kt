package dev.sluide.databases.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.connect.SavedPasswords
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.SessionEvent
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private const val RUNNING_REASON = "A statement is running."
private const val EDITS_REASON = "There are unsubmitted edits."
private const val SUBMIT_REASON = "A submit of edits is running; it may still commit."
private const val CONNECT_THREAD = "sluide-db-connect"

/** The thread that encrypts or decrypts a saved password (may wait for a Keychain prompt). */
internal const val SECRET_THREAD = "sluide-db-secret"

/**
 * The sessions of the [entries] of a [DatabasesTabModel] (ADR 0121): connecting as a cancellable background task,
 * session events mirrored to the entries, disconnecting with its questions, commit, rollback and the commit mode,
 * and the saved passwords' part of connecting (ADR 0122). Nothing here logs a password, a value, a URL or a user. FX
 * thread; results of other threads come back through [fx].
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
    /** The saved passwords (ADR 0122). */
    private val savedPasswords: SavedPasswords,
    /** Sets the saved password (`null`: none) on the entry's current config and saves the list. */
    private val setSaved: (ConnectionEntry, String?) -> Unit,
    /** Called with every password array after it was zeroed; tests check that copies do not stay in memory. */
    private val zeroed: (CharArray) -> Unit = {},
) {
    /**
     * Connects [entry] with the in-memory password, else the saved one (decrypted on the worker, inside the task),
     * else the one asked. Nothing while it is connecting or connected.
     */
    fun connect(entry: ConnectionEntry) {
        val status = entry.status.get()
        if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED) return
        val config = entry.config.get()
        val folder = folder()
        val cached = passwords.get(folder, config.id)
        val saved = config.savedPassword
        when {
            cached != null -> start(entry, config, folder, cached, Source.MEMORY)
            saved != null && !entry.savedRejected -> startSaved(entry, config, folder, saved)
            else -> ask(entry, config, folder)
        }
    }

    private fun ask(
        entry: ConnectionEntry,
        config: ConnectionConfig,
        folder: Path?,
    ) {
        val reason = savedPasswords.unavailableReason()
        val answer = dialogs.askPassword(config, reason, config.savedPassword != null) ?: return
        passwords.put(folder, config.id, answer.password)
        val source =
            when {
                // The box was disabled: whatever it showed, the saved value stays as it is.
                reason != null -> Source.PROMPT_KEEP

                answer.save -> Source.PROMPT_SAVE

                else -> Source.PROMPT
            }
        start(entry, config, folder, answer.password, source)
    }

    private fun begin(
        entry: ConnectionEntry,
        config: ConnectionConfig,
    ): ConnectAttempt {
        lateinit var attempt: ConnectAttempt
        val task = tasks.start("Connecting to ${config.name}") { cancelConnect(entry, attempt) }
        attempt = ConnectAttempt(task, entry.saveGeneration)
        entry.attempt = attempt
        entry.connecting()
        return attempt
    }

    private fun start(
        entry: ConnectionEntry,
        config: ConnectionConfig,
        folder: Path?,
        password: CharArray,
        source: Source,
    ) {
        val attempt = begin(entry, config)
        startWorker(CONNECT_THREAD) { open(entry, attempt, folder, config, password, source) }
    }

    /** Connects with the [saved] password, decrypted on the worker; without one that decrypts, asks. */
    private fun startSaved(
        entry: ConnectionEntry,
        config: ConnectionConfig,
        folder: Path?,
        saved: String,
    ) {
        val attempt = begin(entry, config)
        startWorker(CONNECT_THREAD) {
            val decrypted =
                try {
                    savedPasswords.decrypt(config.id, saved)
                } catch (e: RuntimeException) {
                    Decrypted.NoKey("the saved password could not be read (${e.javaClass.name})")
                }
            when (decrypted) {
                is Decrypted.Plain -> {
                    if (attempt.cancelled) {
                        zero(decrypted.value)
                        fx { attempt.task.failed("cancelled") }
                    } else {
                        open(entry, attempt, folder, config, decrypted.value, Source.SAVED)
                    }
                }

                Decrypted.Unreadable -> {
                    fx { savedUnusable(entry, attempt, saved, unreadable = true) }
                }

                is Decrypted.NoKey -> {
                    fx { savedUnusable(entry, attempt, saved, unreadable = false) }
                }
            }
        }
    }

    /**
     * The saved password did not decrypt: an [unreadable] one is removed (unless it was replaced, or a new one is
     * being saved, meanwhile), one without a key kept; this attempt ends quietly and the password is asked. Nothing
     * more after a cancel.
     */
    private fun savedUnusable(
        entry: ConnectionEntry,
        attempt: ConnectAttempt,
        saved: String,
        unreadable: Boolean,
    ) {
        if (attempt.cancelled || entry.attempt !== attempt || entry !in entries) {
            attempt.task.failed("cancelled")
            return
        }
        val unchanged = entry.saveGeneration == attempt.saveGeneration && entry.config.get().savedPassword == saved
        if (unreadable && unchanged) setSaved(entry, null)
        attempt.task.close()
        entry.attempt = null
        entry.ended(null)
        ask(entry, entry.config.get(), folder())
    }

    /** Opens the session on the worker and zeroes [password]; a prompted one to save goes on as a copy. */
    private fun open(
        entry: ConnectionEntry,
        attempt: ConnectAttempt,
        folder: Path?,
        config: ConnectionConfig,
        password: CharArray,
        source: Source,
    ) {
        val used = password.takeIf { it.isNotEmpty() }
        try {
            val session = openSession(config, used)
            val toSave = if (source == Source.PROMPT_SAVE) password.copyOf() else null
            fx { arrived(entry, attempt, session, source, toSave) }
        } catch (e: Throwable) {
            val message = ConnectFailures.message(e, config, used)
            val auth = ConnectFailures.isAuthFailure(e, config.kind)
            fx { failed(entry, attempt, folder, config, message, auth, source) }
        } finally {
            zero(password)
        }
    }

    private fun arrived(
        entry: ConnectionEntry,
        attempt: ConnectAttempt,
        session: DbSession,
        source: Source,
        toSave: CharArray?,
    ) {
        if (attempt.cancelled || entry.attempt !== attempt || entry !in entries) {
            toSave?.let(::zero)
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
        entry.savedRejected = false
        // Not over a decision taken while connecting (edit, forget, another save).
        val unchanged = entry.saveGeneration == attempt.saveGeneration
        if (toSave != null) {
            if (unchanged) saveLater(entry, toSave, attempt.saveGeneration) else zero(toSave)
        } else if (source == Source.PROMPT && unchanged && entry.config.get().savedPassword != null) {
            setSaved(entry, null)
        }
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
        source: Source,
    ) {
        if (authFailure) {
            // A rejected saved password stays saved, but the next connect asks.
            if (source == Source.SAVED) entry.savedRejected = true else passwords.forget(folder, config.id)
        }
        attempt.task.failed("could not connect")
        if (attempt.cancelled || entry.attempt !== attempt) return
        entry.attempt = null
        entry.ended(message)
    }

    /**
     * Encrypts [plain] on a worker and zeroes it; then sets it on the entry's current config and clears
     * [ConnectionEntry.savedRejected], or says why it was not saved. Nothing once the entry is gone or its saved
     * password changed since [generation].
     */
    fun saveLater(
        entry: ConnectionEntry,
        plain: CharArray,
        generation: Int = entry.saveGeneration,
    ) {
        val id = entry.config.get().id
        startWorker(SECRET_THREAD) {
            val result =
                try {
                    savedPasswords.encrypt(id, plain)
                } catch (e: RuntimeException) {
                    Encrypted.Failed("the password could not be encrypted (${e.javaClass.name})")
                } finally {
                    zero(plain)
                }
            fx { stored(entry, generation, result) }
        }
    }

    private fun stored(
        entry: ConnectionEntry,
        generation: Int,
        result: Encrypted,
    ) {
        if (entry !in entries || entry.saveGeneration != generation) return
        when (result) {
            is Encrypted.Value -> {
                setSaved(entry, result.text)
                entry.savedRejected = false
            }

            is Encrypted.Failed -> {
                dialogs.showError(
                    "Password not saved",
                    "The password stays in memory until Thoreau quits: ${result.reason}.",
                )
            }
        }
    }

    private fun zero(array: CharArray) {
        array.fill('\u0000')
        zeroed(array)
    }

    /**
     * Where the password of a connect came from. After the login, a prompted one with Save checked is saved, one with
     * Save unchecked removes the saved value, and one asked with the box disabled leaves it alone.
     */
    private enum class Source { MEMORY, SAVED, PROMPT, PROMPT_SAVE, PROMPT_KEEP }

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
     * (cancelled), unsubmitted edits (lost)", or `null` if nothing. Edits being submitted are "submit running (may
     * still commit)": the outcome is not known.
     */
    fun closeWarning(entry: ConnectionEntry): String? {
        val session = entry.session.get() ?: return null
        val parts =
            listOfNotNull(
                "uncommitted transaction (rolled back)".takeIf { session.pendingTransaction },
                "statement running (cancelled)".takeIf { session.running },
                if (entry.submitRunning()) {
                    "submit running (may still commit)"
                } else {
                    "unsubmitted edits (lost)".takeIf { entry.unsubmittedEdits() }
                },
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
            if (entry.submitRunning()) SUBMIT_REASON else EDITS_REASON.takeIf { entry.unsubmittedEdits() },
        )
}
