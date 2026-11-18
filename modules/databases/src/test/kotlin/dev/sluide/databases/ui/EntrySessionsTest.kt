package dev.sluide.databases.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.session.SessionEvent
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EntrySessions] on its own, with its collaborators as fakes: the guards that decide when a late result of a connect
 * or a commit still counts, the questions of a disconnect and what each call returns. The model-level flows are in
 * [DatabasesTabModelSessionsTest].
 */
class EntrySessionsTest {
    private val folder = Path.of("project")
    private val config = ConnectionConfig("o", "orders-dev", DatabaseKind.POSTGRESQL, user = "app")
    private val entry = ConnectionEntry(config, "project|o")

    private val passwords = PasswordCache()
    private val taskLines = mutableListOf<String>()
    private val tasks = BackgroundTasks.forTests { taskLines += it }
    private val dialogs = FakeDialogs()
    private val saved = FakeSavedPasswords()
    private val entries = mutableListOf(entry)
    private val workers = mutableListOf<Runnable>()
    private val opened = mutableListOf<FakeSession>()
    private val zeroed = mutableListOf<CharArray>()

    /** What `setSaved` was called with. */
    private val setSaved = mutableListOf<String?>()

    /** Runs while a session opens, before it exists. */
    private var whileOpening: () -> Unit = {}

    /** Runs on the new session, before it is handed to the entry. */
    private var sessionCreated: (FakeSession) -> Unit = {}
    private var openFails: Throwable? = null

    private val sessions =
        EntrySessions(
            passwords,
            tasks,
            dialogs,
            { cfg, _ ->
                whileOpening()
                openFails?.let { throw it }
                FakeSession(cfg).also {
                    sessionCreated(it)
                    opened += it
                }
            },
            { _, work -> workers += work },
            { it.run() },
            { folder },
            entries,
            saved,
            { target, value ->
                setSaved += value
                target.setConfig(target.config.get().copy(savedPassword = value))
                target.saveGeneration++
            },
            { zeroed += it },
        )

    private fun runWorkers() {
        while (workers.isNotEmpty()) {
            val pending = workers.toList()
            workers.clear()
            pending.forEach(Runnable::run)
        }
    }

    private fun cancelTask() {
        tasks.shown
            .single()
            .cancel()
    }

    private fun connected(
        mode: CommitMode = CommitMode.MANUAL,
        pending: Boolean = false,
    ): FakeSession {
        passwords.put(folder, "o", "pw".toCharArray())
        sessions.connect(entry)
        runWorkers()
        return opened.last().also {
            it.commitMode = mode
            it.pendingTransaction = pending
        }
    }

    private fun withSaved(value: String) = entry.setConfig(entry.config.get().copy(savedPassword = value))

    // ---- connect ----

    @Test
    fun `connect leaves a connecting and a connected entry alone`() {
        passwords.put(folder, "o", "pw".toCharArray())
        sessions.connect(entry)
        sessions.connect(entry)
        assertEquals(1, workers.size, "connecting")
        runWorkers()
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())

        sessions.connect(entry)

        assertFalse(workers.isNotEmpty(), "connected")
        assertEquals(1, opened.size)
        assertEquals(emptyList(), dialogs.asked)
    }

    @Test
    fun `a connect remembers the save generation it started with`() {
        entry.saveGeneration = 5
        dialogs.password = "typed".toCharArray()
        dialogs.save = true

        sessions.connect(entry)
        runWorkers()

        assertEquals(listOf<String?>(FakeSavedPasswords.value("o", "typed")), setSaved)
    }

    @Test
    fun `an unreadable saved password is removed when nothing changed since the connect began`() {
        entry.saveGeneration = 5
        withSaved("v1:other:x")
        dialogs.password = null

        sessions.connect(entry)
        runWorkers()

        assertEquals(listOf<String?>(null), setSaved)
        assertNull(entry.attempt)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `after an unreadable saved password the typed one is cached for the project folder`() {
        withSaved("v1:other:x")
        dialogs.password = "typed".toCharArray()

        sessions.connect(entry)
        runWorkers()

        assertEquals("typed", String(checkNotNull(passwords.get(folder, "o"))))
    }

    @Test
    fun `a connect cancelled while an unreadable saved password is decrypted keeps it and asks nothing`() {
        withSaved("v1:other:x")
        saved.duringDecrypt = ::cancelTask

        sessions.connect(entry)
        runWorkers()

        assertEquals(emptyList(), dialogs.asked)
        assertEquals(emptyList(), setSaved)
        assertEquals("v1:other:x", entry.config.get().savedPassword)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertTrue(taskLines.last().contains("cancelled"), taskLines.last())
    }

    @Test
    fun `an entry removed while its saved password is decrypted gets no question and no change`() {
        withSaved("v1:other:x")
        // constructed state: the entry is removed without cancelling its connect (production goes through close/disconnect)
        saved.duringDecrypt = { entries.remove(entry) }

        sessions.connect(entry)
        runWorkers()

        assertEquals(emptyList(), dialogs.asked)
        assertEquals(emptyList(), setSaved)
        assertTrue(taskLines.last().contains("cancelled"), taskLines.last())
    }

    @Test
    fun `an unreadable saved password stays when only the save generation moved during the decrypt`() {
        withSaved("v1:other:x")
        saved.duringDecrypt = { entry.saveGeneration++ }
        dialogs.password = null

        sessions.connect(entry)
        runWorkers()

        assertEquals(emptyList(), setSaved)
        assertEquals("v1:other:x", entry.config.get().savedPassword)
    }

    @Test
    fun `an unreadable saved password stays when another one replaced it during the decrypt`() {
        withSaved("v1:other:x")
        saved.duringDecrypt = { withSaved("v1:o:newer") }
        dialogs.password = null

        sessions.connect(entry)
        runWorkers()

        assertEquals(emptyList(), setSaved)
        assertEquals("v1:o:newer", entry.config.get().savedPassword)
    }

    @Test
    fun `a close ends the attempt that a guard left behind`() {
        withSaved("v1:other:x")
        // constructed state: the entry is removed without cancelling its connect (production goes through close/disconnect)
        saved.duringDecrypt = { entries.remove(entry) }
        sessions.connect(entry)
        runWorkers()
        entries += entry

        sessions.close(entry)

        assertNull(entry.attempt)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `a decrypt that throws is treated like a missing key, the password is kept and asked`() {
        withSaved(FakeSavedPasswords.value("o", "stored"))
        saved.duringDecrypt = { throw IllegalStateException("keychain gone") }
        dialogs.password = null

        sessions.connect(entry)
        runWorkers()

        assertEquals(listOf("password orders-dev"), dialogs.asked)
        assertEquals(emptyList(), setSaved)
        assertEquals(FakeSavedPasswords.value("o", "stored"), entry.config.get().savedPassword)
    }

    @Test
    fun `an encrypt that throws says why the password was not saved`() {
        dialogs.password = "typed".toCharArray()
        dialogs.save = true
        saved.encryptThrows = IllegalStateException("keychain gone")

        sessions.connect(entry)
        runWorkers()

        assertEquals(
            listOf(
                "Password not saved" to
                    "The password stays in memory until Thoreau quits: " +
                    "the password could not be encrypted (java.lang.IllegalStateException).",
            ),
            dialogs.errors,
        )
        assertEquals(emptyList(), setSaved)
    }

    @Test
    fun `a prompted login without Save leaves a config that has no saved password alone`() {
        dialogs.password = "typed".toCharArray()

        sessions.connect(entry)
        runWorkers()

        assertEquals(emptyList(), setSaved)
        assertEquals(0, entry.saveGeneration)
    }

    @Test
    fun `a session that arrives after the entry was removed is closed and its password copy zeroed`() {
        dialogs.password = "typed".toCharArray()
        dialogs.save = true
        // constructed state: the entry is removed without cancelling its connect (production goes through close/disconnect)
        whileOpening = { entries.remove(entry) }

        sessions.connect(entry)
        runWorkers()

        assertEquals(listOf("close"), opened.single().calls)
        assertNull(entry.session.get())
        assertTrue(taskLines.last().contains("cancelled"), taskLines.last())
        assertTrue(zeroed.all { array -> array.all { it == '\u0000' } })
        assertEquals(emptyList(), setSaved)
    }

    @Test
    fun `a connected entry listens to its session, and a close removes the listener`() {
        val session = connected()

        assertNotNull(entry.listener)
        assertEquals(1, session.listeners.size)
        assertNull(entry.attempt)

        sessions.close(entry)

        assertEquals(emptyList(), session.listeners)
        assertEquals(listOf("close"), session.calls)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
    }

    @Test
    fun `a session that was closed before the entry could listen ends the connection with a message`() {
        sessionCreated = { it.closed = true }

        connected()

        assertEquals(ConnectionStatus.ERROR, entry.status.get())
        assertEquals("The connection was closed.", entry.error.get())
    }

    @Test
    fun `a failed connect ends the attempt and shows the message`() {
        passwords.put(folder, "o", "pw".toCharArray())
        openFails = IllegalStateException("no route")

        sessions.connect(entry)
        runWorkers()

        assertNull(entry.attempt)
        assertEquals(ConnectionStatus.ERROR, entry.status.get())
        assertEquals("no route", entry.error.get())
    }

    @Test
    fun `a connect that fails after it was cancelled shows nothing`() {
        passwords.put(folder, "o", "pw".toCharArray())
        openFails = IllegalStateException("no route")
        whileOpening = ::cancelTask

        sessions.connect(entry)
        runWorkers()

        assertNull(entry.attempt)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertEquals("", entry.error.get())
        assertTrue(taskLines.last().contains("cancelled"), taskLines.last())
    }

    // ---- disconnect ----

    @Test
    fun `disconnect cancels a connect in progress, runs then and answers true`() {
        passwords.put(folder, "o", "pw".toCharArray())
        sessions.connect(entry)
        var thens = 0

        val answer = sessions.disconnect(entry, removing = false) { thens++ }

        assertTrue(answer)
        assertEquals(1, thens)
        assertNull(entry.attempt)
        assertEquals(ConnectionStatus.DISCONNECTED, entry.status.get())
        assertTrue(taskLines.last().contains("cancelled"), taskLines.last())
        runWorkers()
        assertEquals(listOf("close"), opened.single().calls, "the session that arrives late")
    }

    @Test
    fun `disconnect without a session runs then and answers true`() {
        var thens = 0

        assertTrue(sessions.disconnect(entry, removing = false) { thens++ })

        assertEquals(1, thens)
        assertEquals(emptyList(), dialogs.asked)
    }

    @Test
    fun `disconnect answers false when the user cancels either question`() {
        val session = connected(pending = true)
        var thens = 0
        dialogs.transactionEnd = null

        assertFalse(sessions.disconnect(entry, removing = false) { thens++ })

        session.pendingTransaction = false
        session.running = true
        dialogs.disconnect = false
        assertFalse(sessions.disconnect(entry, removing = false) { thens++ })
        assertEquals(0, thens)
        assertEquals(listOf("transaction orders-dev", "disconnect orders-dev [A statement is running.]"), dialogs.asked)
        assertEquals(emptyList(), session.calls)
    }

    @Test
    fun `disconnect of a quiet session closes it, runs then once and answers true`() {
        val session = connected()
        var thens = 0

        assertTrue(sessions.disconnect(entry, removing = false) { thens++ })

        assertEquals(1, thens)
        assertEquals(listOf("close"), session.calls)
        assertEquals(emptyList(), dialogs.asked)
    }

    @Test
    fun `a disconnect waiting for its commit blocks a second one, and ends when the commit did`() {
        val session = connected(pending = true)
        session.endsAtOnce = false
        dialogs.transactionEnd = TransactionEnd.COMMIT
        var thens = 0

        assertTrue(sessions.disconnect(entry, removing = false) { thens++ })
        assertTrue(entry.ending)
        assertFalse(sessions.disconnect(entry, removing = false) { thens++ }, "a second click")
        assertEquals(listOf("transaction orders-dev"), dialogs.asked)
        assertEquals(0, thens)

        session.ending.single().complete(Unit)

        assertFalse(entry.ending)
        assertEquals(1, thens)
        assertEquals(listOf("commit", "close"), session.calls)
    }

    @Test
    fun `a roll back that fails before a disconnect is no commit failure, the connection closes`() {
        val session = connected(pending = true)
        session.ends = IllegalStateException("gone")
        dialogs.transactionEnd = TransactionEnd.ROLLBACK
        var thens = 0

        sessions.disconnect(entry, removing = false) { thens++ }

        assertEquals(emptyList(), dialogs.errors)
        assertEquals(1, thens)
        assertEquals(listOf("rollback", "close"), session.calls)
    }

    @Test
    fun `a commit that ends after the entry got another session still lets the disconnect finish`() {
        val session = connected(pending = true)
        session.endsAtOnce = false
        dialogs.transactionEnd = TransactionEnd.COMMIT
        var thens = 0
        sessions.disconnect(entry, removing = false) { thens++ }
        session.emit(SessionEvent.Closed(null))
        assertNull(entry.session.get())

        session.ending.single().complete(Unit)

        assertEquals(1, thens)
        assertEquals(listOf("commit", "close"), session.calls)
    }

    @Test
    fun `a running statement and edits are each a reason to ask before a disconnect`() {
        val session = connected()
        session.running = true
        entry.unsubmittedEdits = { true }
        dialogs.disconnect = false

        sessions.disconnect(entry, removing = false)
        entry.submitRunning = { true }
        sessions.disconnect(entry, removing = false)

        assertEquals(
            listOf(
                "disconnect orders-dev [A statement is running., There are unsubmitted edits.]",
                "disconnect orders-dev [A statement is running., A submit of edits is running; it may still commit.]",
            ),
            dialogs.asked,
        )
    }

    // ---- commit mode, commit and rollback ----

    @Test
    fun `without a session the commit mode, commit and roll back do nothing`() {
        sessions.setCommitMode(entry, CommitMode.AUTO)
        sessions.commit(entry)
        sessions.rollback(entry)

        assertEquals(emptyList(), dialogs.asked)
        assertEquals(emptyList(), dialogs.errors)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a commit mode that is already set is not set again`() {
        val session = connected(mode = CommitMode.MANUAL)

        sessions.setCommitMode(entry, CommitMode.MANUAL)

        assertEquals(emptyList(), session.calls)
    }

    @Test
    fun `only leaving MANUAL with a pending transaction asks how it ends`() {
        val manual = connected(mode = CommitMode.MANUAL, pending = false)
        sessions.setCommitMode(entry, CommitMode.AUTO)
        assertEquals(listOf("mode AUTO"), manual.calls)

        manual.pendingTransaction = true
        sessions.setCommitMode(entry, CommitMode.MANUAL)
        assertEquals(listOf("mode AUTO", "mode MANUAL"), manual.calls, "entering MANUAL asks nothing")
        assertEquals(emptyList(), dialogs.asked)

        dialogs.transactionEnd = TransactionEnd.COMMIT
        sessions.setCommitMode(entry, CommitMode.AUTO)
        assertEquals(listOf("mode AUTO", "mode MANUAL", "commit", "mode AUTO"), manual.calls)
        assertEquals(listOf("transaction orders-dev"), dialogs.asked)
        assertEquals(emptyList(), dialogs.errors)
    }

    @Test
    fun `a result that comes after the entry got another session changes nothing and is not reported`() {
        val session = connected()
        session.endsAtOnce = false
        session.ends = null
        var reported = 0

        sessions.commit(entry) {
            reported++
            true
        }
        session.emit(SessionEvent.Closed(null))
        session.ending.single().completeExceptionally(IllegalStateException("late"))

        assertEquals(0, reported)
        assertEquals(emptyList(), dialogs.errors)
    }

    @Test
    fun `a failed commit is shown, unless a callback handled it or the session is closed`() {
        val session = connected()
        session.endsAtOnce = false
        val failures = mutableListOf<CompletableFuture<Unit>>()

        sessions.commit(entry)
        sessions.commit(entry) { false }
        sessions.rollback(entry) { true }
        failures += session.ending.toList()
        failures[0].completeExceptionally(IllegalStateException("boom"))
        failures[1].completeExceptionally(IllegalStateException("bang"))
        failures[2].completeExceptionally(IllegalStateException("handled"))

        assertEquals(
            listOf("Commit failed" to "orders-dev: boom", "Commit failed" to "orders-dev: bang"),
            dialogs.errors,
        )

        session.closed = true
        sessions.rollback(entry)
        session.ending.last().completeExceptionally(IllegalStateException("closed"))
        assertEquals(2, dialogs.errors.size, "a broken session shows its own end")
    }
}
