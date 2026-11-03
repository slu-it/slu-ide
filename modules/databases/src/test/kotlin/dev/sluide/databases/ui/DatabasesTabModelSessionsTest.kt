package dev.sluide.databases.ui

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.SessionEvent
import dev.sluide.databases.ui.dialog.ConnectionEdit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The session side of [DatabasesTabModel] ([EntrySessions]): connect (with saved passwords), events, disconnect,
 * transactions, close.
 */
class DatabasesTabModelSessionsTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var f: ModelFixture
    private lateinit var project: Path
    private lateinit var entry: ConnectionEntry

    private val orders =
        ConnectionConfig(
            "o",
            "orders-dev",
            DatabaseKind.POSTGRESQL,
            user = "app",
            commitMode = CommitMode.MANUAL,
            customUrl = "jdbc:postgresql://app:hunter2@db/orders",
        )

    @BeforeEach
    fun setUp() {
        f = ModelFixture(dir)
        project = dir.resolve("project").createDirectories()
        f.store.save(project, listOf(orders))
        f.model.load(project)
        entry = f.model.connections.single()
    }

    @Test
    fun `connect with a cached password does not ask, and shows the session's state`() {
        f.passwords.put(project, "o", "cached".toCharArray())

        f.model.connect(entry)
        assertEquals(ConnectionStatus.CONNECTING, entry.status.get())
        assertTrue(f.taskLines.single().endsWith(" started: Connecting to orders-dev"), f.taskLines.single())
        f.runWorkers()

        assertEquals(listOf(), f.dialogs.asked)
        assertEquals(listOf<String?>("cached"), f.openedWith)
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
        assertSame(f.sessions.single(), entry.session.get())
        assertEquals(CommitMode.MANUAL, entry.commitMode.get())
        assertTrue(f.taskLines.last().contains("finished"), f.taskLines.last())
        assertEquals(listOf(), f.tasks.shown)

        f.model.connect(entry)
        assertEquals(1, f.sessions.size, "a connected entry is left alone")
    }

    @Test
    fun `connect asks for a password and caches it, an empty one connects without, Cancel does nothing`() {
        f.model.connect(entry)
        assertEquals(listOf("password orders-dev"), f.dialogs.asked)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertEquals(listOf(), f.taskLines)

        f.dialogs.password = "typed".toCharArray()
        f.model.connect(entry)
        f.model.connect(entry)
        f.runWorkers()
        assertEquals(listOf("password orders-dev", "password orders-dev"), f.dialogs.asked, "connecting is left alone")
        assertEquals(listOf<String?>("typed"), f.openedWith)
        assertEquals("typed", String(checkNotNull(f.passwords.get(project, "o"))))

        f.passwords.forget(project, "o")
        f.model.disconnect(entry)
        f.dialogs.password = CharArray(0)
        f.model.connect(entry)
        f.runWorkers()
        assertEquals(listOf("typed", null), f.openedWith)
    }

    @Test
    fun `a failed connect shows the driver's message without the URL's credentials or the password`() {
        f.passwords.put(project, "o", "hunter2".toCharArray())
        f.connectFailure =
            SQLException("(conn=7) No suitable driver found for jdbc:postgresql://app:hunter2@db/orders", "08001")

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(ConnectionStatus.ERROR, entry.status.get())
        assertEquals("No suitable driver found for jdbc:postgresql://db/orders", entry.error.get())
        assertNull(entry.session.get())
        assertTrue(f.taskLines.last().contains("failed"), f.taskLines.last())
        assertEquals("hunter2", String(checkNotNull(f.passwords.get(project, "o"))), "not a login failure")
    }

    @ParameterizedTest
    @CsvSource("POSTGRESQL,28P01,0", "POSTGRESQL,28000,0", "MARIADB,28000,1045", "MARIADB,HY000,1045")
    fun `a failed login forgets the cached password, so the next connect asks`(
        kind: DatabaseKind,
        sqlState: String,
        code: Int,
    ) {
        f.store.save(project, listOf(orders.copy(kind = kind)))
        f.model.load(project)
        entry = f.model.connections.single()
        f.passwords.put(project, "o", "wrong".toCharArray())
        f.connectFailure = SQLException("password authentication failed", sqlState, code)

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(ConnectionStatus.ERROR, entry.status.get())
        assertEquals("password authentication failed", entry.error.get())
        assertNull(f.passwords.get(project, "o"))
        assertTrue(ConnectFailures.isAuthFailure(SQLException("x", sqlState, code), kind))
        assertFalse(ConnectFailures.isAuthFailure(SQLException("x", "08001", 0), kind))
    }

    @Test
    fun `cancelling the connecting task disconnects at once and closes the session that arrives late`() {
        f.passwords.put(project, "o", "pw".toCharArray())
        f.model.connect(entry)

        f.tasks.shown
            .single()
            .cancel()
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        f.runWorkers()

        assertEquals(listOf("close"), f.sessions.single().calls)
        assertNull(entry.session.get())
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertTrue(f.taskLines.last().contains("cancelled after"), f.taskLines.last())
    }

    @Test
    fun `session events update running and pending, Closed ends the connection with or without an error`() {
        val session = f.connected(entry)

        session.emit(SessionEvent.RunningChanged(true))
        session.emit(SessionEvent.TransactionChanged(true))
        assertTrue(entry.running.get())
        assertTrue(entry.pendingTransaction.get())

        session.emit(SessionEvent.Closed("terminating connection due to idle-in-transaction timeout"))
        assertEquals(ConnectionStatus.ERROR, entry.status.get())
        assertEquals("terminating connection due to idle-in-transaction timeout", entry.error.get())
        assertNull(entry.session.get())
        assertFalse(entry.running.get())
        assertFalse(entry.pendingTransaction.get())

        val second = f.connected(entry)
        assertEquals("", entry.error.get())
        session.emit(SessionEvent.RunningChanged(true))
        assertFalse(entry.running.get(), "events of an older session are ignored")
        second.emit(SessionEvent.Closed(null))
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `disconnect without anything going on closes the session without a question`() {
        val session = f.connected(entry)

        f.model.disconnect(entry)

        assertEquals(listOf(), f.dialogs.asked)
        assertEquals(listOf("close"), session.calls)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertNull(entry.session.get())
    }

    @ParameterizedTest
    @CsvSource("true,false,A statement is running.", "false,true,There are unsubmitted edits.")
    fun `disconnect asks while a statement runs or edits are unsubmitted, and Cancel keeps the connection`(
        running: Boolean,
        edits: Boolean,
        reason: String,
    ) {
        val session = f.connected(entry)
        session.running = running
        entry.unsubmittedEdits = { edits }
        f.dialogs.disconnect = false

        f.model.disconnect(entry)
        assertEquals(listOf("disconnect orders-dev [$reason]"), f.dialogs.asked)
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())

        f.dialogs.disconnect = true
        f.model.disconnect(entry)
        assertEquals(listOf("close"), session.calls)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `disconnect with a pending transaction asks how it ends, Cancel keeps it`() {
        val session = f.connected(entry)
        session.pendingTransaction = true

        f.model.disconnect(entry)

        assertEquals(listOf("transaction orders-dev"), f.dialogs.asked)
        assertEquals(listOf(), session.calls)
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
    }

    @ParameterizedTest
    @CsvSource("COMMIT,commit", "ROLLBACK,rollback")
    fun `disconnect ends a pending transaction as answered, and closes once it ended`(
        end: TransactionEnd,
        call: String,
    ) {
        val session = f.connected(entry)
        session.pendingTransaction = true
        session.running = true
        session.endsAtOnce = false
        f.dialogs.transactionEnd = end

        f.model.disconnect(entry)
        assertEquals(
            listOf("transaction orders-dev", "disconnect orders-dev [A statement is running.]"),
            f.dialogs.asked,
        )
        assertEquals(listOf("cancel", call), session.calls)
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get(), "until it ended")
        f.model.disconnect(entry)
        f.model.delete(entry)
        assertEquals(2, f.dialogs.asked.size, "a second click asks nothing while it ends")

        session.ending.single().complete(Unit)

        assertEquals(listOf("cancel", call, "close"), session.calls)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `a failed commit before a disconnect keeps the connection and says why`() {
        val session = f.connected(entry)
        session.pendingTransaction = true
        session.ends = SQLException("deferred constraint violated")
        f.dialogs.transactionEnd = TransactionEnd.COMMIT

        f.model.disconnect(entry)

        assertEquals(listOf("commit"), session.calls)
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
        assertEquals(
            "Commit failed" to "orders-dev stays connected: deferred constraint violated",
            f.dialogs.errors.single(),
        )
    }

    @Test
    fun `a commit that fails after the window closed the session shows nothing`() {
        val session = f.connected(entry)
        session.pendingTransaction = true
        session.endsAtOnce = false
        f.dialogs.transactionEnd = TransactionEnd.COMMIT
        f.model.disconnect(entry)

        f.model.disconnectAll()
        session.ending.single().completeExceptionally(SQLException("the session is closed"))

        assertEquals(listOf(), f.dialogs.errors)
        assertEquals(listOf("commit", "close", "close"), session.calls)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `a commit that fails because the connection broke shows nothing, its Closed event ends the card`() {
        val session = f.connected(entry)
        session.pendingTransaction = true
        session.endsAtOnce = false
        f.dialogs.transactionEnd = TransactionEnd.COMMIT
        f.model.disconnect(entry)

        session.closed = true
        session.ending.single().completeExceptionally(
            SQLException("An I/O error occurred while sending to the backend"),
        )
        assertEquals(listOf(), f.dialogs.errors)

        session.emit(SessionEvent.Closed("An I/O error occurred while sending to the backend"))
        assertEquals(ConnectionStatus.ERROR, entry.status.get())
    }

    @Test
    fun `a commit that fails while deleting says the transaction was rolled back before the delete goes on`() {
        val session = f.connected(entry)
        session.pendingTransaction = true
        session.endsAtOnce = false
        f.dialogs.transactionEnd = TransactionEnd.COMMIT

        f.model.delete(entry)
        assertEquals(listOf(entry), f.model.connections.toList(), "until the commit ended")

        session.ending.single().completeExceptionally(SQLException("deferred constraint violated"))

        assertEquals(
            "Commit failed" to
                "Commit failed; the transaction was rolled back.\norders-dev: deferred constraint violated",
            f.dialogs.errors.single(),
        )
        assertEquals(listOf("commit", "close"), session.calls)
        assertEquals(listOf(), f.model.connections.toList())
        assertEquals(listOf(), f.store.load(project))
    }

    @Test
    fun `loading the connections again closes the sessions of the old ones without a question`() {
        val session = f.connected(entry)
        session.pendingTransaction = true

        f.model.load(null)

        assertEquals(listOf(), f.dialogs.asked)
        assertEquals(listOf("close"), session.calls)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `MANUAL to AUTO with a pending transaction asks first, Cancel keeps the mode`() {
        val session = f.connected(entry)
        session.pendingTransaction = true

        f.model.setCommitMode(entry, CommitMode.AUTO)
        assertEquals(listOf("transaction orders-dev"), f.dialogs.asked)
        assertEquals(listOf(), session.calls)
        assertEquals(CommitMode.MANUAL, entry.commitMode.get())

        f.dialogs.transactionEnd = TransactionEnd.ROLLBACK
        f.model.setCommitMode(entry, CommitMode.AUTO)
        assertEquals(listOf("rollback", "mode AUTO"), session.calls)
        assertEquals(CommitMode.AUTO, entry.commitMode.get())

        f.model.setCommitMode(entry, CommitMode.MANUAL)
        assertEquals(listOf("rollback", "mode AUTO", "mode MANUAL"), session.calls, "no question towards MANUAL")
        assertEquals(2, f.dialogs.asked.size)
        f.model.setCommitMode(entry, CommitMode.AUTO)
        assertEquals(CommitMode.AUTO, entry.commitMode.get())

        session.emit(SessionEvent.Closed(null))
        assertEquals(CommitMode.MANUAL, entry.commitMode.get(), "the config's while disconnected")
    }

    @Test
    fun `MANUAL to AUTO with a pending transaction commits first when answered so`() {
        val session = f.connected(entry)
        session.pendingTransaction = true
        f.dialogs.transactionEnd = TransactionEnd.COMMIT

        f.model.setCommitMode(entry, CommitMode.AUTO)

        assertEquals(listOf("commit", "mode AUTO"), session.calls)
    }

    @Test
    fun `commit and rollback go to the session, a failure is shown`() {
        val session = f.connected(entry)

        f.model.commit(entry)
        f.model.rollback(entry)
        session.ends = SQLException("could not serialize access")
        f.model.commit(entry)

        assertEquals(listOf("commit", "rollback", "commit"), session.calls)
        assertEquals("Commit failed" to "orders-dev: could not serialize access", f.dialogs.errors.single())
    }

    @Test
    fun `commit and rollback with a callback report the outcome there instead of the dialog`() {
        val session = f.connected(entry)
        val done = mutableListOf<Throwable?>()

        f.model.commit(entry) {
            done += it
            true
        }
        val failure = SQLException("could not serialize access")
        session.ends = failure
        f.model.rollback(entry) {
            done += it
            true
        }

        assertEquals(listOf<Throwable?>(null, failure), done)
        assertEquals(emptyList<Pair<String, String>>(), f.dialogs.errors)
    }

    @Test
    fun `a failure the callback did not handle still shows the error dialog`() {
        val session = f.connected(entry)
        session.ends = SQLException("gone")

        f.model.commit(entry) { false }

        assertEquals("Commit failed" to "orders-dev: gone", f.dialogs.errors.single())
    }

    @Test
    fun `close warnings name each connection with a pending transaction, a running statement, edits or a submit`() {
        f.store.save(
            project,
            listOf(orders, orders.copy(id = "b", name = "billing"), orders.copy(id = "c", name = "c")),
        )
        f.model.load(project)
        val (first, second, third) = f.model.connections
        val a = f.connected(first)
        val b = f.connected(second)
        f.connected(third)
        assertEquals(listOf(), f.model.closeWarnings())

        a.pendingTransaction = true
        b.running = true
        second.unsubmittedEdits = { true }
        third.unsubmittedEdits = { true }
        third.submitRunning = { true }

        assertEquals(
            listOf(
                "orders-dev: uncommitted transaction (rolled back)",
                "billing: statement running (cancelled), unsubmitted edits (lost)",
                "c: submit running (may still commit)",
            ),
            f.model.closeWarnings(),
        )
    }

    @Test
    fun `disconnectAll closes every session and cancels connects in progress without a question`() {
        f.store.save(project, listOf(orders, orders.copy(id = "b", name = "billing")))
        f.model.load(project)
        val (first, second) = f.model.connections
        val a = f.connected(first)
        a.pendingTransaction = true
        f.passwords.put(project, "b", "pw".toCharArray())
        f.model.connect(second)

        f.model.disconnectAll()
        f.runWorkers()

        assertEquals(listOf(), f.dialogs.asked)
        assertEquals(listOf("close"), a.calls)
        assertEquals(listOf("close"), f.sessions.last().calls, "the late session")
        assertEquals(
            listOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.DISCONNECTED),
            f.model.connections.map {
                it.status.get()
            },
        )
        assertEquals(listOf(), f.model.closeWarnings())
    }

    /** Stores orders with the saved password [password] (`null`: none) and makes it the entry. */
    private fun withSaved(value: String?) {
        f.store.save(project, listOf(orders.copy(savedPassword = value)))
        f.model.load(project)
        entry = f.model.connections.single()
    }

    private fun storedValue() =
        f.store
            .load(project)
            .single()
            .savedPassword

    @Test
    fun `connect uses the saved password inside the connecting task without asking, a cached one wins`() {
        withSaved(FakeSavedPasswords.value("o", "stored"))
        f.passwords.put(project, "o", "cached".toCharArray())
        f.model.connect(entry)
        f.runWorkers()
        assertEquals(listOf<String?>("cached"), f.openedWith)
        assertEquals(listOf(), f.saved.calls)

        f.model.disconnect(entry)
        f.passwords.forget(project, "o")
        f.model.connect(entry)
        assertTrue(f.taskLines.last().endsWith(" started: Connecting to orders-dev"), f.taskLines.last())
        assertEquals(listOf(), f.saved.calls, "decrypted on the worker")
        f.runWorkers()

        assertEquals(listOf("decrypt o v1:o:stored"), f.saved.calls)
        assertEquals(listOf<String?>("cached", "stored"), f.openedWith)
        assertEquals(listOf(), f.dialogs.asked)
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
        assertTrue(f.saved.allZeroed())
        assertNull(f.passwords.get(project, "o"), "a saved password is not kept in memory")
    }

    @Test
    fun `an unreadable saved password is removed, and the password asked`() {
        withSaved("v1:elsewhere:x")

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(listOf("password orders-dev"), f.dialogs.asked)
        assertEquals(listOf<Pair<String?, Boolean>>(null to false), f.dialogs.prompts)
        assertNull(storedValue())
        assertNull(entry.config.get().savedPassword)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertTrue(f.taskLines.last().contains("finished"), f.taskLines.last())
        assertEquals(listOf(), f.openedWith)
    }

    @Test
    fun `without a key the saved password is kept, and the password asked with Save checked`() {
        val value = FakeSavedPasswords.value("o", "stored")
        withSaved(value)
        f.saved.noKey = "Keychain access denied"
        f.saved.reason = "Keychain access denied"

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(listOf<Pair<String?, Boolean>>("Keychain access denied" to true), f.dialogs.prompts)
        assertEquals(value, storedValue())
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get(), "the prompt was cancelled")
        assertEquals(listOf(), f.openedWith)
    }

    @Test
    fun `cancelling while the saved password is decrypted neither asks nor connects`() {
        withSaved(FakeSavedPasswords.value("o", "stored"))
        f.saved.duringDecrypt = {
            f.tasks.shown
                .single()
                .cancel()
        }

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(listOf(), f.dialogs.asked)
        assertEquals(listOf(), f.openedWith)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertTrue(f.saved.allZeroed())
        assertTrue(f.taskLines.last().contains("cancelled after"), f.taskLines.last())
    }

    @Test
    fun `a prompted password with Save checked is saved once the login succeeded, not after a failed one`() {
        f.dialogs.password = "typed".toCharArray()
        f.dialogs.save = true
        f.connectFailure = SQLException("password authentication failed", "28P01")

        f.model.connect(entry)
        f.runWorkers()
        assertEquals(listOf(), f.saved.calls)
        assertNull(storedValue())

        f.connectFailure = null
        f.model.connect(entry)
        f.runWorkers()

        assertEquals(listOf("encrypt o typed"), f.saved.calls)
        assertEquals(FakeSavedPasswords.value("o", "typed"), storedValue())
        assertEquals(FakeSavedPasswords.value("o", "typed"), entry.config.get().savedPassword)
        assertEquals(listOf(), f.dialogs.errors)
    }

    @Test
    fun `a password that cannot be saved stays in memory, and the dialog says why`() {
        f.saved.encryptFails = "needs secret-tool"
        f.dialogs.password = "typed".toCharArray()
        f.dialogs.save = true

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(
            listOf("Password not saved" to "The password stays in memory until Thoreau quits: needs secret-tool."),
            f.dialogs.errors,
        )
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
        assertEquals("typed", String(checkNotNull(f.passwords.get(project, "o"))))
        assertNull(storedValue())
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `a rejected saved password stays until a new one logs in, replaced when Save is checked, else removed`(
        save: Boolean,
    ) {
        val old = FakeSavedPasswords.value("o", "old")
        withSaved(old)
        f.connectFailure = SQLException("password authentication failed", "28P01")
        f.model.connect(entry)
        f.runWorkers()
        assertEquals(ConnectionStatus.ERROR, entry.status.get())
        assertEquals(old, storedValue())

        f.connectFailure = SQLException("password authentication failed", "28P01")
        f.dialogs.password = "new".toCharArray()
        f.dialogs.save = save
        f.model.connect(entry)
        f.runWorkers()
        assertEquals(listOf<Pair<String?, Boolean>>(null to true), f.dialogs.prompts)
        assertEquals(1, f.saved.calls.count { it.startsWith("decrypt") }, "the rejected one is not used again")
        assertEquals(old, storedValue(), "kept after a failed login")

        f.connectFailure = null
        f.model.connect(entry)
        f.runWorkers()

        assertEquals(listOf<String?>("old", "new", "new"), f.openedWith)
        assertEquals(if (save) FakeSavedPasswords.value("o", "new") else null, storedValue())
        assertFalse(entry.savedRejected)
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `with the Save box disabled a prompted login neither saves nor removes the saved password`(save: Boolean) {
        val value = FakeSavedPasswords.value("o", "stored")
        withSaved(value)
        f.saved.noKey = "Keychain access denied"
        f.saved.reason = "Keychain access denied"
        f.dialogs.password = "typed".toCharArray()
        f.dialogs.save = save

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
        assertEquals(value, storedValue())
        assertEquals(listOf(), f.saved.calls.filter { it.startsWith("encrypt") })
        assertEquals(listOf(), f.dialogs.errors)
    }

    @Test
    fun `an unreadable saved password that was replaced during the decrypt stays`() {
        withSaved("v1:elsewhere:x")
        val replaced = FakeSavedPasswords.value("o", "new")
        f.saved.duringDecrypt = { f.model.setSavedPassword(entry, replaced) }

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(replaced, storedValue())
        assertEquals(listOf("password orders-dev"), f.dialogs.asked)
    }

    @Test
    fun `a password to save is zeroed when the connect was cancelled after the login`() {
        f.dialogs.password = "typed".toCharArray()
        f.dialogs.save = true
        f.duringOpen = {
            f.tasks.shown
                .single()
                .cancel()
        }

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(listOf("close"), f.sessions.single().calls)
        assertEquals(listOf(), f.saved.calls)
        assertEquals(2, f.zeroed.size, "the prompt's answer and the copy to save")
        assertTrue(f.zeroed.all { array -> array.all { it == '\u0000' } })
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `a password saved in the dialog while a prompted login runs survives that login`(save: Boolean) {
        withSaved(FakeSavedPasswords.value("o", "old"))
        entry.savedRejected = true
        f.dialogs.password = "typed".toCharArray()
        f.dialogs.save = save
        f.model.connect(entry)

        f.dialogs.edit = { ConnectionEdit(it, "new".toCharArray(), savePassword = true) }
        f.model.edit(entry)
        f.runWorkers()

        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
        assertEquals(FakeSavedPasswords.value("o", "new"), storedValue())
        assertEquals(listOf("encrypt o new"), f.saved.calls)
        // The dialog's password and its copy to encrypt, the prompt's answer and, with Save, its stale copy to save.
        assertEquals(if (save) 4 else 3, f.zeroed.size)
        assertTrue(f.zeroed.all { array -> array.all { it == '\u0000' } })
    }

    @Test
    fun `an unreadable saved password is not removed when a new one was saved during the decrypt`() {
        withSaved("v1:elsewhere:x")
        f.dialogs.edit = { ConnectionEdit(it, "new".toCharArray(), savePassword = true) }
        f.saved.duringDecrypt = { f.model.edit(entry) }

        f.model.connect(entry)
        f.runWorkers()

        assertEquals(FakeSavedPasswords.value("o", "new"), storedValue())
        assertEquals(listOf("password orders-dev"), f.dialogs.asked.filter { it.startsWith("password") })
    }
}
