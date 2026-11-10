package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Restarts and closes that meet a running server or a start in progress. */
class LanguageServerRacesTest {
    @TempDir
    lateinit var folder: Path

    private val spec = TestSpec()
    private val scheduler = FakeScheduler()
    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
    }

    private fun servers(
        starter: FakeStarter,
        spec: TestSpec = this.spec,
        gate: TrustGate = TrustGate { _, _ -> true },
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
        dispatch: (Runnable) -> Unit = Runnable::run,
    ) = LanguageServers(listOf(spec), { folder }, gate, dispatch, starter, scheduler, timing, System::nanoTime)
        .also { servers = it }

    private fun LanguageServers.awaitState(expected: ServerState) =
        awaitTrue("state $expected") { state(spec).value == expected }

    @Test
    fun `a running server is announced with its connection in one FX step, so work queued on seeing it comes after`() {
        val fx = LinkedBlockingQueue<Runnable>()
        val session = servers(FakeStarter(), dispatch = { fx.add(it) }).session(spec)
        var connected = false
        session.onConnected { connected = true }

        session.ensureStarted()
        while (session.state.value != ServerState.Running) {
            checkNotNull(fx.poll(5, TimeUnit.SECONDS)) { "no FX step, state ${session.state.value}" }.run()
        }

        assertTrue(connected, "connected announced in the step that set Running")
    }

    @Test
    fun `a gate that flips while the document's didOpen is still queued sends didOpen, then didClose, in order`() {
        val marker = folder.resolve("old")
        val spec =
            TestSpec(
                gatesDocuments = true,
                gates = { file, _ -> DocumentGate(!(file.fileName.toString() == "x.py" && Files.exists(marker))) },
                affects = { true },
            )
        val starter = FakeStarter()
        val servers = servers(starter, spec = spec)
        val session = servers.session(spec)
        session.documents.open(folder.resolve("a.py"), "python", "a")
        awaitTrue("opened on the server it started") {
            starter.processes.isNotEmpty() && "didOpen a" in starter.last.server.calls
        }
        val server = starter.last.server
        val hold = CountDownLatch(1)
        session.notify { hold.await(5, TimeUnit.SECONDS) }

        session.documents.open(folder.resolve("x.py"), "python", "x")
        Files.createFile(marker)
        servers.filesChanged(listOf(FileChange(marker, FileChangeKind.CREATED)))
        scheduler.runNext()
        hold.countDown()

        awaitTrue("closed") { "didClose" in server.calls }
        assertEquals(listOf("didOpen a", "didOpen x", "didClose"), server.calls.filter { it.startsWith("did") })
        server.assertClosesFollowOpens()
    }

    @Test
    fun `a restart ends the running server politely, ignores its end and counts restarts from the start again`() {
        val starter = FakeStarter()
        val servers = servers(starter)
        servers.session(spec).ensureStarted()
        servers.awaitState(ServerState.Running)
        starter.last.crash()
        servers.awaitState(ServerState.Restarting(1))
        scheduler.runNext()
        awaitTrue("second server running") {
            starter.processes.size == 2 &&
                servers.state(spec).value == ServerState.Running
        }
        val old = starter.last

        servers.restart(spec)

        awaitTrue("third server running") {
            starter.processes.size == 3 &&
                servers.state(spec).value == ServerState.Running
        }
        assertTrue(old.awaitEndHandled())
        assertEquals(listOf("shutdown", "exit"), old.server.calls.drop(2))
        assertFalse(old.destroyed)
        assertEquals(ServerState.Running, servers.state(spec).value)
        assertEquals(emptyList(), scheduler.due)
        starter.last.crash()
        servers.awaitState(ServerState.Restarting(1))
    }

    @Test
    fun `closing during the trust wait cancels it and starts nothing`() {
        val waiting = CountDownLatch(1)
        val sawCancel = CountDownLatch(1)
        val gate =
            TrustGate { _, cancelled ->
                waiting.countDown()
                while (!cancelled()) pause()
                sawCancel.countDown()
                false
            }
        val starter = FakeStarter()
        val spec = TestSpec(runsProjectCode = true, trustCheckId = "check")
        val servers = servers(starter, spec, gate)
        servers.session(spec).ensureStarted()
        assertTrue(waiting.await(5, TimeUnit.SECONDS))

        servers.close()

        assertTrue(sawCancel.await(5, TimeUnit.SECONDS))
        assertEquals(ServerState.Stopped, servers.state(spec).value)
        assertEquals(0, starter.processes.size)
    }

    @Test
    fun `a restart during the trust wait cancels it and waits again for the new start`() {
        val asked = CopyOnWriteArrayList<Boolean>()
        val gate =
            TrustGate { _, cancelled ->
                val first = asked.isEmpty()
                asked += first
                // The first wait lasts until the restart replaces it; the second is allowed at once.
                while (first && !cancelled()) pause()
                !first
            }
        val starter = FakeStarter()
        val spec = TestSpec(runsProjectCode = true, trustCheckId = "check")
        val servers = servers(starter, spec, gate)
        servers.session(spec).ensureStarted()
        awaitTrue("first trust wait") { asked.isNotEmpty() }

        servers.restart(spec)

        awaitTrue("running") { servers.state(spec).value == ServerState.Running }
        assertEquals(listOf(true, false), asked)
        assertEquals(1, starter.processes.size)
    }

    @Test
    fun `closing while initialize is unanswered ends the server`() {
        val starter = FakeStarter(newServer = { FakeServer().apply { answerInitialize = false } })
        val servers = servers(starter)
        servers.session(spec).ensureStarted()
        awaitTrue("initialize sent") { starter.processes.size == 1 && "initialize" in starter.last.server.calls }
        val process = starter.last

        servers.close()

        assertTrue(process.awaitEndHandled())
        assertEquals(ServerState.Stopped, servers.state(spec).value)
        assertEquals(emptyList(), scheduler.due)
    }

    @Test
    fun `a restart while initialize is unanswered starts the next server at once`() {
        val starter = FakeStarter(newServer = { index -> FakeServer().apply { answerInitialize = index > 0 } })
        val servers = servers(starter)
        servers.session(spec).ensureStarted()
        awaitTrue("initialize sent") { starter.processes.size == 1 && "initialize" in starter.last.server.calls }
        val first = starter.last

        servers.restart(spec)

        awaitTrue("second server running") {
            starter.processes.size == 2 &&
                servers.state(spec).value == ServerState.Running
        }
        assertTrue(first.awaitEndHandled())
        assertEquals(ServerState.Running, servers.state(spec).value)
    }

    @Test
    fun `no answer to initialize in time counts as a crash`() {
        val starter = FakeStarter(newServer = { FakeServer().apply { answerInitialize = false } })
        val servers = servers(starter, timing = ServerTiming(initialize = 300.milliseconds))

        servers.session(spec).ensureStarted()

        servers.awaitState(ServerState.Restarting(1))
        assertTrue(starter.last.destroyed)
    }

    @Test
    fun `a process whose start was replaced while it started is killed`() {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starter =
            FakeStarter(beforeStart = { index ->
                if (index == 0) {
                    starting.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            })
        val servers = servers(starter)
        servers.session(spec).ensureStarted()
        assertTrue(starting.await(5, TimeUnit.SECONDS))

        servers.restart(spec)
        release.countDown()

        awaitTrue("second server running") {
            starter.processes.size == 2 &&
                servers.state(spec).value == ServerState.Running
        }
        assertTrue(starter.processes[0].destroyed)
        assertEquals(listOf("initialize", "initialized"), starter.processes[1].server.calls)
    }

    @Test
    fun `a process whose start was closed while it started is killed`() {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starter =
            FakeStarter(beforeStart = {
                starting.countDown()
                // Like ProcessBuilder.start, which close's interrupt does not stop.
                awaitUninterruptibly(release)
            })
        val servers = servers(starter)
        servers.session(spec).ensureStarted()
        assertTrue(starting.await(5, TimeUnit.SECONDS))

        servers.close()
        release.countDown()

        awaitTrue("process killed") { starter.processes.size == 1 && starter.processes[0].destroyed }
        assertEquals(emptyList(), starter.processes[0].server.calls)
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await(5, TimeUnit.SECONDS)
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** A trust check's poll interval; an interrupt (the session closing) ends it like a cancel. */
    private fun pause() {
        try {
            Thread.sleep(10) // The poll interval of the callers' `while (!cancelled())` loops, as ProjectTrust's.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
