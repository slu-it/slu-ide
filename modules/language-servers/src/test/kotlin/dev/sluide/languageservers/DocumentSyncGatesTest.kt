package dev.sluide.languageservers

import dev.sluide.languageservers.files.FakeDirectoryWatcher
import dev.sluide.languageservers.workspace.Resolution
import javafx.beans.InvalidationListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a document does with the answer of the spec's folder worker (ADR 0151, ADR 0160): late answers for something
 * else, the fail-closed wait, the document's moves while it waits, `adopt` and the reopen on the server. The worker's
 * tasks wait in [held] until the test runs them, so that an answer is pending for as long as the test wants.
 */
internal class DocumentSyncGatesTest : DocumentSyncTestBase() {
    private val held = LinkedBlockingQueue<Runnable>()
    private val fired = AtomicInteger()
    private val logs = LogRecords(Logger.getLogger(DocumentSync::class.java.name))

    @AfterEach
    fun closeLogs() = logs.close()

    private val watcher = FakeDirectoryWatcher()

    private fun gatedSession(spec: TestSpec = gating): LanguageServerSession =
        session(spec = spec, folderWorker = { Executor { held.add(it) } }, directoryWatcher = watcher).also { session ->
            onFx { session.workspaceChanges.addListener(InvalidationListener { fired.incrementAndGet() }) }
        }

    /** Runs the oldest task the folder worker holds: its answer completes the document's resolution. */
    private fun answer() = checkNotNull(held.poll(5, TimeUnit.SECONDS)) { "no folder task held" }.run()

    /** Everything queued on the FX thread so far has run. */
    private fun settle() = onFx {}

    private fun LanguageServerSession.gate(name: String) = onFx { gateOf(folder.resolve(name)) }

    private fun LanguageServerSession.connected(): FakeServer {
        awaitTrue("connected") { connects.get() >= 1 }
        return starter.last.server
    }

    @Test
    fun `an answer that comes for the document's earlier resolution changes nothing, the current one does`() {
        val session = gatedSession()
        val document = session.open("a.py", "x")
        onFx { document.moved(folder.resolve("b.py")) }

        answer()
        settle()

        assertNull(session.gate("b.py"), "the answer was for a.py")
        assertEquals(0, fired.get())
        assertEquals(emptyList(), starter.processes)

        answer()
        settle()

        assertEquals(DocumentGate(true), session.gate("b.py"))
        val server = session.connected()
        session.flush(server)
        assertEquals(listOf(uri("b.py")), server.opens.map { it.textDocument.uri })
    }

    @Test
    fun `an answer that comes after the document closed changes nothing`() {
        val session = gatedSession()
        val document = session.open("a.py", "x")
        onFx { document.close() }

        answer()
        settle()

        assertNull(session.gate("a.py"))
        assertEquals(0, fired.get())
        assertEquals(emptyList(), starter.processes)
    }

    @Test
    fun `an answer that does not come in time counts as not served, the late answer then serves the document`() {
        val session = gatedSession()
        session.open("a.py", "x")

        scheduler.runNext()
        settle()

        assertEquals(DocumentGate(false), session.gate("a.py"))
        assertEquals(1, fired.get())
        assertEquals(
            listOf("lsp test: no gate answer for ${folder.resolve("a.py")} yet; not served"),
            logs.messages(Level.WARNING),
        )
        assertEquals(ServerState.Stopped, onFx { session.state.value })
        assertEquals(emptyList(), starter.processes)

        answer()
        settle()

        assertEquals(DocumentGate(true), session.gate("a.py"))
        assertEquals(2, fired.get())
        val server = session.connected()
        session.flush(server)
        assertEquals(listOf("x"), server.opens.map { it.textDocument.text })
    }

    @Test
    fun `the wait of an earlier resolution is cancelled by the next one and does nothing if it fires anyway`() {
        val session = gatedSession()
        val document = session.open("a.py", "x")
        val earlier = scheduler.due.single().second

        onFx { document.moved(folder.resolve("b.py")) }

        assertEquals(1, scheduler.due.size, "the new resolution waits, the old wait is cancelled")
        earlier.run()
        settle()
        assertNull(session.gate("b.py"))
        assertEquals(0, fired.get())
        assertEquals(emptyList(), logs.messages(Level.WARNING))
    }

    @Test
    fun `a wait that fires after the answer came in the same step does not unserve the document`() {
        val session = gatedSession()
        session.open("a.py", "x")
        val wait = scheduler.due.single().second

        // The answer is queued to the FX thread first, the wait second: the answer is there when the wait runs.
        onFx {
            answer()
            wait.run()
        }
        settle()

        assertEquals(DocumentGate(true), session.gate("a.py"))
        assertEquals(1, fired.get())
        assertEquals(emptyList(), logs.messages(Level.WARNING))
        val server = session.connected()
        session.flush(server)
        assertEquals(listOf("x"), server.opens.map { it.textDocument.text })
        assertEquals(emptyList(), server.closes)
    }

    @Test
    fun `closing a document cancels its wait, and a wait that fires anyway does nothing`() {
        val session = gatedSession()
        val document = session.open("a.py", "x")
        val wait = scheduler.due.single().second

        onFx { document.close() }

        assertEquals(0, scheduler.due.size)
        wait.run()
        settle()
        assertNull(session.gate("a.py"))
        assertEquals(0, fired.get())
        assertEquals(emptyList(), logs.messages(Level.WARNING))
    }

    @Test
    fun `adopt takes a resolution, pending it waits, answered it applies and tells whether it sent anything`() {
        val session = gatedSession()
        session.open("a.py", "x")
        val document = onFx { session.documents.workspaceDocuments().single() }

        assertFalse(onFx { document.adopt(CompletableFuture()) })
        assertEquals(1, scheduler.due.size, "one wait: the first one was cancelled")

        val served = CompletableFuture.completedFuture(Resolution(null, DocumentGate.SERVED))
        assertTrue(onFx { document.adopt(served) }, "opened on the server")
        assertEquals(0, scheduler.due.size)
        assertFalse(onFx { document.adopt(served) }, "nothing changed")
        assertEquals(DocumentGate.SERVED, session.gate("a.py"))
        session.connected()
    }

    @Test
    fun `a reopen closes and opens a served document again, a document that is not served stays away`() {
        val session = session(spec = gating)
        session.open("a.py", "a")
        val server = session.connected()
        session.open("old.py", "o")

        onFx { session.documents.workspaceDocuments().forEach { it.reopenOnServer() } }
        session.flush(server)

        // The first open went to the starting server and was dropped, the one on connect is version 2.
        assertEquals(listOf("open a.py 2", "close a.py", "open a.py 3"), server.log())
        server.assertClosesFollowOpens()
    }

    @Test
    fun `a moved document whose answer was there is not served while the new one is awaited, and says so`() {
        val session = gatedSession()
        val document = session.open("a.py", "x")
        answer()
        settle()
        val before = fired.get()

        onFx { document.moved(folder.resolve("b.py")) }

        assertNull(session.gate("b.py"))
        assertEquals(before + 1, fired.get())

        answer()
        settle()

        assertEquals(DocumentGate(true), session.gate("b.py"))
        assertEquals(before + 2, fired.get())
        session.connected()
    }

    @Test
    fun `a moved document that had no answer yet changes nothing for its listeners`() {
        val session = gatedSession()
        val document = session.open("a.py", "x")

        onFx { document.moved(folder.resolve("b.py")) }

        assertNull(session.gate("b.py"))
        assertEquals(0, fired.get())
    }

    @Test
    fun `a moved document answered at once fires once, for the new answer`() {
        val session = session(spec = gating)
        val document = session.open("a.py", "x")
        session.connected()
        val listener = AtomicInteger()
        onFx { session.workspaceChanges.addListener(InvalidationListener { listener.incrementAndGet() }) }

        onFx { document.moved(folder.resolve("b.py")) }

        assertEquals(DocumentGate(true), session.gate("b.py"))
        assertEquals(1, listener.get())
    }

    @Test
    fun `a moved document of a spec that does not gate stays served and open while its folder is asked`() {
        val session = gatedSession(spec)
        val document = session.open("a.py", "x")
        session.connected()
        assertEquals(0, scheduler.due.size, "no wait: a spec that does not gate has no fail-closed answer")

        onFx { document.moved(folder.resolve("b.py")) }

        assertEquals(DocumentGate.SERVED, session.gate("b.py"))
        assertEquals(0, fired.get())
        assertEquals(0, scheduler.due.size)
        assertTrue(onFx { (session.documents.workspaceDocuments().single() as DocumentSync.Document).isOpenOnServer })
    }

    @Test
    fun `a moved document stops naming the folders its old answer watched at once, not when the new answer comes`() {
        val watching =
            TestSpec(gatesDocuments = true, gates = { file, _ ->
                DocumentGate(
                    true,
                    watched =
                        if (file.fileName.toString() ==
                            "a.py"
                        ) {
                            listOf(folder.resolve("w"))
                        } else {
                            emptyList()
                        },
                )
            })
        val session = gatedSession(watching)
        val document = session.open("a.py", "x")
        answer()
        settle()
        session.connected()
        // The worker's tasks so far (the watch of w, and whatever the connect asked for) run now.
        awaitTrue("w watched") {
            generateSequence { held.poll() }.forEach(Runnable::run)
            folder.resolve("w") in watcher.listeners
        }
        settle()
        generateSequence { held.poll() }.forEach(Runnable::run)

        onFx { document.moved(folder.resolve("b.py")) }

        assertEquals(2, held.size, "the end of the watch of w and the question for b.py")
        answer()
        assertFalse(folder.resolve("w") in watcher.listeners)
        session.connected()
    }

    @Test
    fun `an unanswered wait keeps the folder the document had, the gate is what it takes away`() {
        val inSub = TestSpec(gatesDocuments = true, folders = { _, project -> project.resolve("sub") })
        val session = gatedSession(inSub)
        val document = session.open("a.py", "x")
        answer()
        settle()
        val sub = folder.resolve("sub")
        assertEquals(sub, onFx { session.workspaceFolderOf(folder.resolve("a.py")).value })

        onFx { document.moved(folder.resolve("b.py")) }
        scheduler.runNext()
        settle()

        assertEquals(sub, onFx { session.workspaceFolderOf(folder.resolve("b.py")).value })
        assertEquals(DocumentGate(false), session.gate("b.py"))
    }

    @Test
    fun `a folder or a gate that changes is shown and fires once, the same answer again does not`() {
        val session = gatedSession()
        session.open("a.py", "x")
        val document = onFx { session.documents.workspaceDocuments().single() }
        val sub = folder.resolve("sub")
        val watched = DocumentGate(true, watched = listOf(folder.resolve("w")))

        fun adopt(answer: Resolution) = onFx { document.adopt(CompletableFuture.completedFuture(answer)) }

        assertTrue(adopt(Resolution(null, DocumentGate.SERVED)))
        assertEquals(1, fired.get())
        assertFalse(adopt(Resolution(null, DocumentGate.SERVED)))
        assertEquals(1, fired.get())

        assertFalse(adopt(Resolution(sub, DocumentGate.SERVED)), "only the folder changed: nothing to send")
        assertEquals(2, fired.get())
        assertEquals(sub, onFx { session.workspaceFolderOf(folder.resolve("a.py")).value })

        assertFalse(adopt(Resolution(sub, watched)))
        assertEquals(3, fired.get(), "only the gate changed")
        assertFalse(adopt(Resolution(sub, watched)))
        assertEquals(3, fired.get())
        session.connected()
    }

    @Test
    fun `an answer adopted by a document that closed or that another one moved onto is not applied`() {
        val session = gatedSession()
        val closing = session.open("a.py", "x")
        val closed = onFx { session.documents.workspaceDocuments().single() }
        val victim = session.open("b.py", "y")
        val staleDocument = onFx { session.documents.workspaceDocuments().first { it.file == folder.resolve("b.py") } }
        val moving = session.open("c.py", "z")
        onFx { moving.moved(folder.resolve("b.py")) }
        onFx { closing.close() }
        val served = CompletableFuture.completedFuture(Resolution(null, DocumentGate.SERVED))

        assertFalse(onFx { closed.adopt(served) }, "closed")
        assertFalse(onFx { staleDocument.adopt(served) }, "another document moved onto its file")

        assertEquals(emptyList(), starter.processes)
        assertEquals(0, fired.get())
        onFx { victim.close() }
    }

    @Test
    fun `a server that was not available starts for the next served document of a gating spec`() {
        val unavailable = TestSpec(command = null, gatesDocuments = true)
        val session = session(spec = unavailable)
        session.open("a.py", "x")
        awaitTrue("unavailable") { onFx { session.state.value } == ServerState.Unavailable }
        unavailable.command = ServerCommand(Path.of("fake-server"))

        session.open("b.py", "y")

        awaitTrue("started by the served document") { onFx { session.state.value } == ServerState.Running }
    }

    @Test
    fun `a server that was not available is not started again by a document of a spec that does not gate`() {
        val asked = AtomicInteger()
        val unavailable = TestSpec(command = null, commandFor = { asked.incrementAndGet().let { null } })
        val session = session(spec = unavailable)
        session.open("a.py", "x")
        awaitTrue("unavailable") { onFx { session.state.value } == ServerState.Unavailable }
        unavailable.command = ServerCommand(Path.of("fake-server"))
        val before = asked.get()

        session.open("b.py", "y")
        // Queued behind any start the document would have made, and answered not running.
        val errors = CopyOnWriteArrayList<Throwable>()
        session.request<Any>({ CompletableFuture() }, {}, { errors += it })
        awaitTrue("not running") { errors.isNotEmpty() }

        assertEquals(before, asked.get())
        assertEquals(ServerState.Unavailable, onFx { session.state.value })
    }

    @Test
    fun `a document whose answer is pending starts nothing, whatever asks for a start`() {
        val session = gatedSession()
        session.open("a.py", "x")
        val errors = CopyOnWriteArrayList<Throwable>()

        onFx {
            session.ensureStarted()
            session.restart()
            session.request<Any>({ CompletableFuture() }, {}, { errors += it })
        }

        awaitTrue("not running") { errors.isNotEmpty() }
        assertEquals(emptyList(), starter.processes)
        assertEquals(ServerState.Stopped, onFx { session.state.value })
    }

    @Test
    fun `a document that never reached the new server is not closed on it`() {
        val release = CountDownLatch(1)
        val session = session(spec = gating, beforeStart = { if (it == 1) release.await(5, TimeUnit.SECONDS) })
        val document = session.open("a.py", "x")
        awaitTrue("first server") { connects.get() == 1 }
        val first = starter.last.server
        session.flush(first)
        onFx { session.restart() }
        awaitTrue("the second start is held") {
            starter.processes.size == 1 &&
                onFx { session.state.value } != ServerState.Running
        }

        // Its didClose has no server to go to, and the document's didOpen epoch stays that of the first one.
        onFx { document.moved(folder.resolve("old.py")) }
        release.countDown()
        awaitTrue("second server") { connects.get() == 2 }
        val second = starter.last.server
        onFx { document.close() }
        session.flush(second)

        assertEquals(emptyList(), second.documentNotifications)
        second.assertClosesFollowOpens()
    }

    @Test
    fun `closing a document that is not served logs no failed notification and sends nothing`() {
        val session = session(spec = gating)
        session.open("a.py", "a")
        awaitTrue("started by the served document") { connects.get() == 1 }
        val server = starter.last.server
        val old = session.open("old.py", "x")
        val infos = LogRecords(Logger.getLogger(LanguageServerSession::class.java.name))

        onFx { old.close() }
        session.flush(server)
        infos.close()

        assertEquals(emptyList(), infos.messages(Level.INFO).filter { "notification failed" in it })
        assertEquals(listOf("open a.py 2"), server.log())
    }

    private fun unavailableWithServedDocument(): Pair<TestSpec, LanguageServerSession> {
        val unavailable = TestSpec(command = null, gatesDocuments = true)
        val session = session(spec = unavailable)
        session.open("a.py", "x")
        awaitTrue("unavailable") { onFx { session.state.value } == ServerState.Unavailable }
        unavailable.command = ServerCommand(Path.of("fake-server"))
        return unavailable to session
    }

    @Test
    fun `a server that was not available starts for a served document when it is asked to`() {
        val (_, session) = unavailableWithServedDocument()

        onFx { session.ensureStarted() }

        awaitTrue("started by ensureStarted") { onFx { session.state.value } == ServerState.Running }
    }

    @Test
    fun `a server that was not available starts for a served document on a restart`() {
        val (_, session) = unavailableWithServedDocument()

        onFx { session.restart() }

        awaitTrue("started by restart") { onFx { session.state.value } == ServerState.Running }
    }
}
