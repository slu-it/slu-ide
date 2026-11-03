package dev.sluide.databases.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.ConnectionStore
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.NoSavedPasswords
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.connect.SavedPasswords
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.ui.dialog.ConnectionEdit
import dev.sluide.databases.ui.dialog.ConnectionTester
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import javafx.application.Platform
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

private val log = System.getLogger(DatabasesTabModel::class.java.name)

/**
 * The connections of a window's Databases tab (ADR 0121): the cards in their stored order, the selected one, and what
 * the cards' actions do. It outlives the tab, so closing the tab keeps the connections. Every change of the list saves
 * it whole through the [store]; typed passwords go into the in-memory [passwords], saved ones only encrypted by
 * [savedPasswords] into the config (ADR 0122). Connecting, disconnecting and the transaction actions are
 * [EntrySessions]'. FX thread.
 */
class DatabasesTabModel internal constructor(
    private val store: ConnectionStore,
    private val passwords: PasswordCache,
    tasks: BackgroundTasks,
    private val dialogs: DatabaseDialogs,
    /** The dialog's Test Connection. */
    private val tester: ConnectionTester,
    /** Opens a session for a config and password (blocking, never called on the FX thread). */
    openSession: (ConnectionConfig, CharArray?) -> DbSession,
    /** Runs a blocking connect off the FX thread: thread name, work. */
    private val startWorker: (String, Runnable) -> Unit = { name, work -> NamedThreads.virtual(name, work) },
    /** Hands results of background work to the FX thread. */
    private val fx: (Runnable) -> Unit = Platform::runLater,
    /** The id of a new or duplicated connection. */
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Encrypts and decrypts the saved passwords (blocking, on the workers). */
    private val savedPasswords: SavedPasswords = NoSavedPasswords,
) {
    private val entries = FXCollections.observableArrayList<ConnectionEntry>()

    /** The cards in their order; a read-only view for the UI. */
    val connections: ObservableList<ConnectionEntry> = FXCollections.unmodifiableObservableList(entries)

    /** The card whose console and tree are shown. */
    val selected: ObjectProperty<ConnectionEntry?> = SimpleObjectProperty()

    /** The project folder the connections were loaded from; `null` for the shared ones. */
    internal var folder: Path? = null
        private set

    /** Test hook: called with every password array after it was zeroed. */
    internal var onZeroed: (CharArray) -> Unit = {}

    private val sessions =
        EntrySessions(
            passwords,
            tasks,
            dialogs,
            openSession,
            startWorker,
            fx,
            { folder },
            entries,
            savedPasswords,
            ::setSavedPassword,
            { onZeroed(it) },
        )

    /** Runs the work of a future on a worker, e.g. decrypting a saved password for Test Connection. */
    private val worker = Executor { work -> startWorker(SECRET_THREAD, work) }

    /**
     * Loads the connections of [folder] (`null`: the shared ones), replacing the current ones; sessions of those are
     * closed here without a question. The window's folder changes at most once, from none to a folder: `ide` asks
     * before with [closeWarnings] while the shared connections have work.
     */
    internal fun load(folder: Path?) {
        entries.forEach(sessions::close)
        this.folder = folder
        selected.set(null)
        entries.setAll(store.load(folder).map { ConnectionEntry(it, historyKey(folder, it.id)) })
    }

    /**
     * Opens the Add dialog for a new [kind] connection; on OK appends, selects and saves it. A password to save is
     * encrypted on a worker and saved once that returns.
     */
    fun add(kind: DatabaseKind) {
        val initial = ConnectionConfig(newId(), uniqueName(kind.label), kind)
        val reason = savedPasswords.unavailableReason()
        val edit = dialogs.editConnection(initial, true, names(), dialogTester(initial.id, null), reason) ?: return
        val config = edit.config.copy(id = initial.id, savedPassword = null)
        val entry = ConnectionEntry(config, historyKey(folder, config.id))
        entries += entry
        selected.set(entry)
        save()
        savePassword(entry, edit, canSave = reason == null)
    }

    /**
     * Opens the Edit dialog for [entry]; on OK replaces its config and saves. A connected entry keeps its session: the
     * new values apply at the next connect. The saved password stays unless the dialog says otherwise: unchecked
     * removes it at once, a typed one to save replaces it once encrypted on a worker. While nothing can be saved (the
     * box disabled), it stays whatever the box says.
     */
    fun edit(entry: ConnectionEntry) {
        val old = entry.config.get()
        val reason = savedPasswords.unavailableReason()
        val test = dialogTester(old.id, old.savedPassword)
        val edit = dialogs.editConnection(old, false, names(), test, reason) ?: return
        val keep = reason != null || edit.savePassword
        // The current value: a connect may have saved one while the dialog was open.
        val saved = if (keep) entry.config.get().savedPassword else null
        if (!keep) entry.savedRejected = false
        entry.saveGeneration++
        entry.setConfig(edit.config.copy(id = old.id, savedPassword = saved))
        save()
        savePassword(entry, edit, canSave = reason == null)
    }

    /**
     * Adds a copy of [entry] after it, named "<name> (copy)" (or "(copy 2)", …) with a new id, selected and saved. A
     * saved password is encrypted again for the copy on a worker (it may ask for the key); if that fails the copy has
     * none.
     */
    fun duplicate(entry: ConnectionEntry) {
        val index = entries.indexOf(entry)
        if (index < 0) return
        val old = entry.config.get()
        val config = old.copy(id = newId(), name = copyName(old.name, names()), savedPassword = null)
        val copy = ConnectionEntry(config, historyKey(folder, config.id))
        entries.add(index + 1, copy)
        selected.set(copy)
        save()
        old.savedPassword?.let { copySavedPassword(old.id, it, copy) }
    }

    /** Removes the saved password of [entry] and forgets the in-memory one; no question. */
    fun forgetSavedPassword(entry: ConnectionEntry) {
        if (entry !in entries) return
        setSavedPassword(entry, null)
        passwords.forget(folder, entry.config.get().id)
        entry.savedRejected = false
    }

    /**
     * Sets the saved password (`null`: none) on the current config of [entry], if it is still listed, and saves; work
     * started before with the old [ConnectionEntry.saveGeneration] no longer applies.
     */
    internal fun setSavedPassword(
        entry: ConnectionEntry,
        value: String?,
    ) {
        if (entry !in entries) return
        entry.saveGeneration++
        val config = entry.config.get()
        if (config.savedPassword == value) return
        entry.setConfig(config.copy(savedPassword = value))
        save()
    }

    /**
     * The typed password of a dialog's [edit] goes into memory and, to save when it [canSave], to a worker that
     * encrypts it.
     */
    private fun savePassword(
        entry: ConnectionEntry,
        edit: ConnectionEdit,
        canSave: Boolean,
    ) {
        val password = edit.password ?: return
        passwords.put(folder, entry.config.get().id, password)
        if (canSave && edit.savePassword) sessions.saveLater(entry, password.copyOf())
        zero(password)
    }

    /**
     * Decrypts the saved [value] of [oldId] and encrypts it for [copy] on a worker; on success sets it on the copy,
     * unless the copy's saved password changed meanwhile.
     */
    private fun copySavedPassword(
        oldId: String,
        value: String,
        copy: ConnectionEntry,
    ) {
        val id = copy.config.get().id
        val generation = copy.saveGeneration
        startWorker(SECRET_THREAD) {
            val encrypted =
                try {
                    reencrypt(oldId, value, id)
                } catch (e: RuntimeException) {
                    log.log(System.Logger.Level.WARNING, "Copying a saved password failed: {0}", e.javaClass.name)
                    null
                }
            if (encrypted is Encrypted.Value) {
                fx { if (copy.saveGeneration == generation) setSavedPassword(copy, encrypted.text) }
            } else {
                log.log(System.Logger.Level.INFO, "The saved password was not copied to the duplicated connection")
            }
        }
    }

    /** [value] saved for [oldId], encrypted for [newId]; `null` when it does not decrypt. Blocking. */
    private fun reencrypt(
        oldId: String,
        value: String,
        newId: String,
    ): Encrypted? {
        val decrypted = savedPasswords.decrypt(oldId, value) as? Decrypted.Plain ?: return null
        return try {
            savedPasswords.encrypt(newId, decrypted.value)
        } finally {
            zero(decrypted.value)
        }
    }

    /**
     * The dialog's Test Connection for connection [id]: a typed password goes to the [tester]; with the field empty,
     * the in-memory password, else the [saved] one decrypted on a worker (failing when it cannot be read), else none.
     * Copies are zeroed once done.
     */
    private fun dialogTester(
        id: String,
        saved: String?,
    ) = ConnectionTester { config, typed ->
        val cached = if (typed == null) passwords.get(folder, id) else null
        when {
            typed != null -> {
                tester.test(config, typed)
            }

            cached != null -> {
                testZeroing(config, cached)
            }

            saved != null -> {
                CompletableFuture
                    .supplyAsync({ savedPasswords.decrypt(id, saved) }, worker)
                    .thenCompose { decrypted ->
                        when (decrypted) {
                            is Decrypted.Plain -> testZeroing(config, decrypted.value)
                            Decrypted.Unreadable -> unreadable("it was saved with another key")
                            is Decrypted.NoKey -> unreadable(decrypted.reason)
                        }
                    }
            }

            else -> {
                tester.test(config, null)
            }
        }
    }

    private fun unreadable(reason: String): CompletableFuture<String> =
        CompletableFuture.failedFuture(SQLException("The saved password could not be read: $reason"))

    /** Tests with [password] and zeroes it once the test completed. */
    private fun testZeroing(
        config: ConnectionConfig,
        password: CharArray?,
    ): CompletableFuture<String> {
        val test =
            try {
                tester.test(config, password)
            } catch (e: RuntimeException) {
                CompletableFuture.failedFuture(e)
            }
        return test.whenComplete { _, _ -> password?.let(::zero) }
    }

    private fun zero(array: CharArray) {
        array.fill('\u0000')
        onZeroed(array)
    }

    /**
     * Deletes [entry] after confirmDelete: disconnects it first (with the questions of [disconnect], whose Cancel keeps
     * it), then, once a chosen Commit or Roll Back has ended, removes it, saves and forgets its password. Nothing while
     * an earlier disconnect of it still ends its transaction.
     */
    fun delete(entry: ConnectionEntry) {
        if (entry !in entries || entry.ending) return
        if (!dialogs.confirmDelete(entry.config.get().name)) return
        sessions.disconnect(entry, removing = true) { remove(entry) }
    }

    private fun remove(entry: ConnectionEntry) {
        val index = entries.indexOf(entry)
        // Gone already: the connections were loaded again meanwhile.
        if (index < 0) return
        entry.saveGeneration++
        entries.removeAt(index)
        if (selected.get() === entry) selected.set(entries.getOrNull(index) ?: entries.lastOrNull())
        save()
        passwords.forget(folder, entry.config.get().id)
    }

    /** Moves the card at [from] to [to]; saved. */
    fun move(
        from: Int,
        to: Int,
    ) {
        if (from !in entries.indices || to !in entries.indices || from == to) return
        val entry = entries.removeAt(from)
        entries.add(to, entry)
        save()
    }

    /** Whether the tree of [entry] shows the system schemas; saved. */
    fun setShowSystemSchemas(
        entry: ConnectionEntry,
        show: Boolean,
    ) {
        val config = entry.config.get()
        if (config.showSystemSchemas == show || entry !in entries) return
        entry.setConfig(config.copy(showSystemSchemas = show))
        save()
    }

    /**
     * Connects [entry] unless it is connecting or connected: with the cached password, else the one asked (whose
     * Cancel does nothing), as a cancellable background task.
     */
    fun connect(entry: ConnectionEntry) = sessions.connect(entry)

    /**
     * Disconnects [entry], asking first how a pending transaction ends, and whether to disconnect while a statement
     * runs or grid edits are unsubmitted; Cancel keeps the connection.
     */
    fun disconnect(entry: ConnectionEntry) {
        sessions.disconnect(entry, removing = false)
    }

    /** Switches the commit mode of [entry]'s session; MANUAL→AUTO with a pending transaction asks how it ends first. */
    fun setCommitMode(
        entry: ConnectionEntry,
        mode: CommitMode,
    ) = sessions.setCommitMode(entry, mode)

    /** Commits [entry]'s transaction; [onDone] (FX thread) gets the failure or `null`; true means it reported the failure, else the error dialog shows. */
    fun commit(
        entry: ConnectionEntry,
        onDone: ((Throwable?) -> Boolean)? = null,
    ) = sessions.commit(entry, onDone)

    /** Rolls back [entry]'s transaction, like [commit]. */
    fun rollback(
        entry: ConnectionEntry,
        onDone: ((Throwable?) -> Boolean)? = null,
    ) = sessions.rollback(entry, onDone)

    /** One line per connection with a pending transaction, a running statement or unsubmitted edits. */
    internal fun closeWarnings(): List<String> = entries.mapNotNull(sessions::closeWarning)

    /** The window closes: every session is closed (rolling back), connects in progress are cancelled; no question. */
    internal fun disconnectAll() = sessions.closeAll()

    private fun save() {
        if (!store.save(folder, entries.map { it.config.get() })) {
            dialogs.showError(
                "Connections not saved",
                "The connections could not be written to the project data. The change stays until the window closes.",
            )
        }
    }

    private fun names(): Set<String> = entries.mapTo(LinkedHashSet()) { it.config.get().name }

    private fun uniqueName(base: String): String {
        val taken = names().map { it.lowercase() }.toSet()
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base $it" }
            .first { it.lowercase() !in taken }
    }

    internal companion object {
        /** The query history key of a connection: `"<folder or empty>|<id>"`. */
        fun historyKey(
            folder: Path?,
            id: String,
        ): String = "${folder ?: ""}|$id"

        /** "<name> (copy)", else "<name> (copy 2)", "(copy 3)", …: the first not among [taken] (ignoring case). */
        fun copyName(
            name: String,
            taken: Set<String>,
        ): String {
            val lower = taken.map { it.lowercase() }.toSet()
            return generateSequence(1) { it + 1 }
                .map { if (it == 1) "$name (copy)" else "$name (copy $it)" }
                .first { it.lowercase() !in lower }
        }
    }
}
