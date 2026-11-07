package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LanguageServersTest {
    @TempDir
    lateinit var folder: Path

    private var starter = FakeStarter()
    private val scheduler = FakeScheduler()
    private val clock = AtomicLong()
    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
    }

    private fun servers(
        spec: TestSpec,
        gate: TrustGate = TrustGate { _, _ -> true },
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
        projectFolder: () -> Path? = { folder },
    ) = LanguageServers(listOf(spec), projectFolder, gate, Runnable::run, starter, scheduler, timing, clock::get)
        .also { servers = it }

    private fun LanguageServers.awaitState(
        spec: TestSpec,
        expected: ServerState,
    ) = awaitTrue("state $expected, is ${state(spec).value}") { state(spec).value == expected }

    @Test
    fun `the file of a matching spec inside the project folder has a server, others have none`() {
        val spec = TestSpec()
        var projectFolder: Path? = folder
        val servers = servers(spec, projectFolder = { projectFolder })

        assertSame(spec, servers.specFor(folder.resolve("pkg/a.py")))
        assertNull(servers.specFor(folder.resolve("notes.md")))
        assertNull(servers.specFor(folder.resolveSibling("elsewhere.py")))
        projectFolder = null
        assertNull(servers.specFor(folder.resolve("pkg/a.py")))
    }

    @Test
    fun `a server starts on first use, in the project folder`() {
        val spec = TestSpec()
        val servers = servers(spec)
        assertEquals(ServerState.Stopped, servers.state(spec).value)
        assertEquals(0, starter.processes.size)

        servers.session(spec).ensureStarted()

        servers.awaitState(spec, ServerState.Running)
        assertEquals(listOf(folder), starter.roots)
    }

    @Test
    fun `a crashed server restarts after 1, 4 and 15 seconds, then fails until the user restarts it`() {
        val spec = TestSpec()
        val servers = servers(spec, timing = ServerTiming())
        servers.session(spec).ensureStarted()
        servers.awaitState(spec, ServerState.Running)

        for ((attempt, delay) in listOf(1 to 1.seconds, 2 to 4.seconds, 3 to 15.seconds)) {
            starter.last.crash()
            servers.awaitState(spec, ServerState.Restarting(attempt))
            assertEquals(delay, scheduler.runNext())
            servers.awaitState(spec, ServerState.Running)
        }
        starter.last.crash()

        servers.awaitState(spec, ServerState.Failed("Test language server exited with code 1"))
        assertEquals(emptyList(), scheduler.due)
        servers.session(spec).ensureStarted()
        assertEquals(4, starter.processes.size)
        servers.restart(spec)
        servers.awaitState(spec, ServerState.Running)
        assertEquals(5, starter.processes.size)
    }

    @Test
    fun `a server that ran for a while before it crashed counts its restarts from the start again`() {
        val spec = TestSpec()
        val servers = servers(spec, timing = ServerTiming())
        servers.session(spec).ensureStarted()
        servers.awaitState(spec, ServerState.Running)
        starter.last.crash()
        servers.awaitState(spec, ServerState.Restarting(1))
        scheduler.runNext()
        servers.awaitState(spec, ServerState.Running)

        clock.addAndGet(3.minutes.inWholeNanoseconds)
        starter.last.crash()

        servers.awaitState(spec, ServerState.Restarting(1))
    }

    @Test
    fun `without a command the server is unavailable and starts once one is there`() {
        val spec = TestSpec(command = null)
        val servers = servers(spec)

        servers.session(spec).ensureStarted()

        servers.awaitState(spec, ServerState.Unavailable)
        assertEquals(0, starter.processes.size)
        spec.command = ServerCommand(Path.of("installed"))
        servers.session(spec).ensureStarted()
        servers.awaitState(spec, ServerState.Running)
    }

    @Test
    fun `a server that runs project code starts only once the trust allows its check`() {
        val asked = CopyOnWriteArrayList<String>()
        val decided = CountDownLatch(1)
        val gate =
            TrustGate { checkId, _ ->
                asked += checkId
                decided.await(5, TimeUnit.SECONDS)
            }
        val spec = TestSpec(runsProjectCode = true, trustCheckId = "test-check")
        val servers = servers(spec, gate)

        servers.session(spec).ensureStarted()
        awaitTrue("trust asked") { asked.isNotEmpty() }

        assertEquals(ServerState.Starting, servers.state(spec).value)
        assertEquals(0, starter.processes.size)
        decided.countDown()
        servers.awaitState(spec, ServerState.Running)
        assertEquals(listOf("test-check"), asked)
    }

    @Test
    fun `a server that runs project code never starts while the trust refuses`() {
        val spec = TestSpec(runsProjectCode = true, trustCheckId = "test-check")
        val servers = servers(spec, TrustGate { _, _ -> false })

        servers.session(spec).ensureStarted()

        servers.awaitState(spec, ServerState.Untrusted)
        assertEquals(0, starter.processes.size)
    }

    @Test
    fun `closing sends shutdown and exit and leaves the exiting server alone`() {
        val spec = TestSpec()
        val servers = servers(spec)
        servers.session(spec).ensureStarted()
        servers.awaitState(spec, ServerState.Running)
        val process = starter.last

        servers.close()

        assertTrue(process.awaitEndHandled())
        assertEquals(listOf("shutdown", "exit"), process.server.calls.drop(2))
        assertFalse(process.destroyed)
        assertEquals(ServerState.Stopped, servers.state(spec).value)
        assertEquals(emptyList(), scheduler.due)
    }

    @Test
    fun `closing still sends exit to a server that does not answer shutdown, and kills it if it stays`() {
        val spec = TestSpec()
        val servers = servers(spec)
        servers.session(spec).ensureStarted()
        servers.awaitState(spec, ServerState.Running)
        val process = starter.last
        process.server.answerShutdown = false
        process.server.honourExit = false

        servers.close()

        assertTrue(process.awaitEndHandled())
        assertTrue(process.destroyed)
        assertEquals(listOf("shutdown", "exit"), process.server.calls.drop(2))
        assertEquals(ServerState.Stopped, servers.state(spec).value)
        assertEquals(emptyList(), scheduler.due)
    }

    @Test
    fun `a request or notification does not start a server that was unavailable, ensureStarted does`() {
        val spec = TestSpec(command = null)
        val servers = servers(spec)
        val session = servers.session(spec)
        session.ensureStarted()
        servers.awaitState(spec, ServerState.Unavailable)
        spec.command = ServerCommand(Path.of("installed"))
        val errors = CopyOnWriteArrayList<Throwable>()

        session.notify { it.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(emptyList())) }
        session.request({ it.shutdown() }, {}, { errors.add(it) })

        awaitTrue("request refused") { errors.isNotEmpty() }
        assertIs<LanguageServerNotRunningException>(errors.single())
        assertEquals(ServerState.Unavailable, servers.state(spec).value)
        assertEquals(0, starter.processes.size)
        session.ensureStarted()
        servers.awaitState(spec, ServerState.Running)
    }

    @Test
    fun `ensureStarted asks the trust again for a server it refused before`() {
        var allowed = false
        val spec = TestSpec(runsProjectCode = true, trustCheckId = "test-check")
        val servers = servers(spec, TrustGate { _, _ -> allowed })
        servers.session(spec).ensureStarted()
        servers.awaitState(spec, ServerState.Untrusted)

        allowed = true
        servers.session(spec).ensureStarted()

        servers.awaitState(spec, ServerState.Running)
    }

    @Test
    fun `a project folder that cannot be read fails the start instead of leaving it starting`() {
        val spec = TestSpec()
        val servers = servers(spec, projectFolder = { throw IllegalStateException("no folder today") })

        servers.session(spec).ensureStarted()

        servers.awaitState(spec, ServerState.Failed("Test language server could not start: no folder today"))
    }

    @Test
    fun `a starter that fails unexpectedly fails the start`() {
        starter = FakeStarter(beforeStart = { throw IllegalArgumentException("bad working folder") })
        val spec = TestSpec()
        val servers = servers(spec)

        servers.session(spec).ensureStarted()

        servers.awaitState(spec, ServerState.Failed("Test language server could not start: bad working folder"))
        assertEquals(emptyList(), scheduler.due)
    }
}
