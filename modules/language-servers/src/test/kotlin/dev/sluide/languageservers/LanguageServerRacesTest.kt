package dev.sluide.languageservers

import dev.sluide.languageservers.connection.LspConnection
import dev.sluide.languageservers.connection.ServerTiming
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
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

    /** What the connections log: whether they gave up on a killed process. */
    private val connectionLogs = LogRecords(Logger.getLogger(LspConnection::class.java.name))

    @AfterEach
    fun closeServers() {
        servers?.close()
        connectionLogs.close()
    }

    private fun servers(
        starter: FakeStarter,
        spec: TestSpec = this.spec,
        gate: TrustGate = TrustGate { _, _ -> true },
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
        dispatch: (Runnable) -> Unit = Runnable::run,
        nanoTime: () -> Long = System::nanoTime,
    ) = LanguageServers(listOf(spec), { folder }, gate, dispatch, starter, scheduler, timing, nanoTime)
        .also { servers = it }

    /**
     * A spec that notes at each `command(projectFolder)` call which of the processes started so far are over: ended,
     * or given up on after the kill's wait. The Java spec deletes jdtls's data folder there (ADR 0179).
     */
    private fun recordingSpec(
        starter: () -> FakeStarter,
        seen: MutableList<List<Boolean>>,
        id: String = "test",
    ) = TestSpec(id = id, commandFor = { _ ->
        seen += starter().processes.map { it.isEnded || gaveUpOn(it) }
        null
    })

    private fun gaveUpOn(process: FakeProcess) =
        connectionLogs.messages(Level.INFO).any { it.startsWith("lsp ${process.name} still runs") }

    private fun LanguageServers.awaitRunning(
        spec: TestSpec,
        processes: Int,
        starter: FakeStarter,
    ) = awaitTrue("server $processes or later running") {
        starter.processes.size >= processes && state(spec).value == ServerState.Running
    }

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
    fun `a restart ends the running server politely, ignores its end, counts restarts anew, cancels a due one`() {
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

        servers.restart(spec)

        assertEquals(emptyList(), scheduler.due)
    }

    @Test
    fun `a restart starts the next server only once the old process has ended`() {
        val seen = CopyOnWriteArrayList<List<Boolean>>()
        lateinit var starter: FakeStarter
        starter = FakeStarter(holdsEnd = { it == 0 })
        val spec = recordingSpec({ starter }, seen)
        val servers = servers(starter, spec)
        servers.session(spec).ensureStarted()
        servers.awaitRunning(spec, 1, starter)
        val old = starter.last

        servers.restart(spec)
        assertTrue(old.awaitEndAsked())
        old.release()

        servers.awaitRunning(spec, 2, starter)
        assertEquals(listOf(emptyList(), listOf(true)), seen)
    }

    @Test
    fun `two restarts while the old process ends wait for it, and the next one for the server they started`() {
        val seen = CopyOnWriteArrayList<List<Boolean>>()
        lateinit var starter: FakeStarter
        starter = FakeStarter(holdsEnd = { it == 0 })
        val spec = recordingSpec({ starter }, seen)
        val servers = servers(starter, spec)
        servers.session(spec).ensureStarted()
        servers.awaitRunning(spec, 1, starter)
        val old = starter.last

        servers.restart(spec)
        assertTrue(old.awaitEndAsked())
        servers.restart(spec)
        old.release()
        servers.awaitRunning(spec, 2, starter)
        servers.restart(spec)

        servers.awaitRunning(spec, 3, starter)
        assertEquals(listOf(emptyList(), listOf(true), listOf(true, true)), seen)
    }

    @Test
    fun `closing while the next start waits for the old process starts nothing`() {
        val seen = CopyOnWriteArrayList<List<Boolean>>()
        lateinit var starter: FakeStarter
        starter = FakeStarter(holdsEnd = { it == 0 })
        // An id of its own: the test waits for the outbound thread of exactly this session to end.
        val spec = recordingSpec({ starter }, seen, id = "close-while-waiting")
        val servers = servers(starter, spec)
        servers.session(spec).ensureStarted()
        servers.awaitRunning(spec, 1, starter)
        val old = starter.last
        servers.restart(spec)
        assertTrue(old.awaitEndAsked())

        servers.close()

        awaitTrue("the waiting start ended") {
            Thread.getAllStackTraces().keys.none { it.name == "sluide-lsp-close-while-waiting" }
        }
        old.release()
        assertEquals(ServerState.Stopped, servers.state(spec).value)
        assertEquals(listOf(emptyList<Boolean>()), seen)
        assertEquals(1, starter.processes.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["failed start", "unanswered initialize"])
    fun `the start after a killed server waits until the kill gave up on a process that does not die`(how: String) {
        val seen = CopyOnWriteArrayList<List<Boolean>>()
        val failed = how == "failed start"
        lateinit var starter: FakeStarter
        // Never released: a process that dies at once would race the next start against the test's release.
        starter =
            FakeStarter(
                newServer = { index -> FakeServer().apply { answerInitialize = failed || index > 0 } },
                holdsEnd = { it == 0 },
            )
        val spec = recordingSpec({ starter }, seen)
        val calls = AtomicInteger()
        val timing = ServerTiming(initialize = 300.milliseconds, shutdown = 200.milliseconds, exit = 300.milliseconds)
        val servers =
            servers(
                starter,
                spec,
                timing = timing,
                // The first call comes once the connection is kept: an unexpected exception there fails the start.
                nanoTime = { if (failed && calls.getAndIncrement() == 0) error("no clock") else System.nanoTime() },
            )
        servers.session(spec).ensureStarted()
        val killed =
            if (failed) {
                ServerState.Failed(
                    "Test language server could not start: no clock",
                )
            } else {
                ServerState.Restarting(1)
            }
        awaitTrue(
            "killed",
        ) { servers.state(spec).value == killed && starter.processes.firstOrNull()?.destroyed == true }

        if (failed) servers.restart(spec) else scheduler.runNext()

        servers.awaitRunning(spec, 2, starter)
        assertEquals(listOf(emptyList(), listOf(true)), seen)
        assertFalse(starter.processes[0].isEnded)
        starter.processes[0].release()
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
    fun `closing while initialize is unanswered ends the server, and a restart after the close starts nothing`() {
        val starter = FakeStarter(newServer = { FakeServer().apply { answerInitialize = false } })
        val servers = servers(starter)
        servers.session(spec).ensureStarted()
        awaitTrue("initialize sent") { starter.processes.size == 1 && "initialize" in starter.last.server.calls }
        val process = starter.last

        servers.close()

        assertTrue(process.awaitEndHandled())
        assertEquals(ServerState.Stopped, servers.state(spec).value)
        assertEquals(emptyList(), scheduler.due)

        servers.restart(spec)

        assertEquals(ServerState.Stopped, servers.state(spec).value)
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
        // The old process is killed after the state is published, outside the lifecycle's lock.
        awaitTrue("the unanswered server destroyed") { starter.last.destroyed }
    }

    @Test
    fun `a process whose start was replaced while it started is killed, and the next start waits for its end`() {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<List<Boolean>>()
        lateinit var starter: FakeStarter
        starter =
            FakeStarter(beforeStart = { index ->
                if (index == 0) {
                    starting.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }, holdsEnd = { it == 0 })
        val spec = recordingSpec({ starter }, seen)
        val servers = servers(starter, spec)
        servers.session(spec).ensureStarted()
        assertTrue(starting.await(5, TimeUnit.SECONDS))

        servers.restart(spec)
        release.countDown()
        awaitTrue("first process killed") { starter.processes.firstOrNull()?.awaitEndAsked() == true }
        starter.processes[0].release()

        servers.awaitRunning(spec, 2, starter)
        assertTrue(starter.processes[0].destroyed)
        // `initialized` is a notification: the fake records it on its own thread, after Running is published.
        awaitTrue("initialized received") { "initialized" in starter.processes[1].server.calls }
        assertEquals(listOf("initialize", "initialized"), starter.processes[1].server.calls)
        assertEquals(listOf(emptyList(), listOf(true)), seen)
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

    @Test
    fun `a start that fails after the close leaves the server stopped`() {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starter =
            FakeStarter(beforeStart = {
                starting.countDown()
                awaitUninterruptibly(release)
                throw IllegalStateException("too late")
            })
        // An id of its own: the test waits for the outbound thread of exactly this session to end.
        val spec = TestSpec(id = "fails-after-close")
        val servers = servers(starter, spec)
        servers.session(spec).ensureStarted()
        assertTrue(starting.await(5, TimeUnit.SECONDS))

        servers.close()
        release.countDown()

        awaitTrue("the failed start ended") {
            Thread.getAllStackTraces().keys.none { it.name == "sluide-lsp-fails-after-close" }
        }
        assertEquals(ServerState.Stopped, servers.state(spec).value)
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
