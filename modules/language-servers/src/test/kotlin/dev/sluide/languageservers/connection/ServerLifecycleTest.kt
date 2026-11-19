package dev.sluide.languageservers.connection

import dev.sluide.languageservers.FakeProcess
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LogRecords
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.TestSpec
import dev.sluide.languageservers.TrustGate
import dev.sluide.languageservers.awaitTrue
import dev.sluide.languageservers.process.ServerProcess
import dev.sluide.languageservers.process.ServerStarter
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The start, the crash and restart policy and the close of one server, driven without a session. */
internal class ServerLifecycleTest : ServerLifecycleTestBase() {
    private val logs = LogRecords(Logger.getLogger(ServerLifecycle::class.java.name))

    @AfterEach
    fun closeLogs() = logs.close()

    private fun adjusting(adjust: (ClientCapabilities) -> Unit): LanguageServerSpec {
        val base = TestSpec()
        return object : LanguageServerSpec by base {
            override fun adjustCapabilities(capabilities: ClientCapabilities) = adjust(capabilities)
        }
    }

    private fun stateNames() = states.map { it.first }

    @Test
    fun `a start tells the server which process runs it, what the spec adjusted and that tracing is off`() {
        val starter = fakeStarter()

        started(lifecycle(adjusting { it.general.positionEncodings = listOf("adjusted") }))

        val params = starter.last.server.initializeParams!!
        assertEquals(ProcessHandle.current().pid().toInt(), params.processId)
        assertEquals("off", params.trace)
        assertEquals(listOf("adjusted"), params.capabilities.general.positionEncodings)
        assertEquals("Thoreau", params.clientInfo.name)
    }

    @Test
    fun `each server that runs is announced with the next connection number, each process with its own number`() {
        val starter = fakeStarter()
        val lifecycle = started(lifecycle())

        lifecycle.restart()
        awaitRunning(2)

        assertEquals(listOf(ServerState.Running to 1, ServerState.Running to 2), states.filter { it.second != null })
        val numbers = starter.processes.map { Regex("test#(\\d+)").matchEntire(it.name)!!.groupValues[1].toInt() }
        assertEquals(2, numbers.size)
        assertTrue(numbers[0] < numbers[1], "$numbers")
    }

    @Test
    fun `a server that runs or starts is not started again, a failed one or one waiting for its restart neither`() {
        val starter = fakeStarter(beforeStart = { if (it > 0) throw IOException("boom") })
        val lifecycle = started(lifecycle())
        val running = states.toList()

        lifecycle.ensureStarted(true)
        flushOutbound(lifecycle)
        assertEquals(running, states.toList())
        assertEquals(1, starter.processes.size)

        starter.last.crash()
        awaitState("restarting") { it == ServerState.Restarting(1) }
        val waiting = states.toList()
        lifecycle.ensureStarted(true)
        assertEquals(waiting, states.toList())
        repeat(3) { scheduler.runNext() }
        awaitState("failed") { it is ServerState.Failed }
        val failed = states.toList()
        lifecycle.ensureStarted(true)
        assertEquals(failed, states.toList())
    }

    @Test
    fun `a start in progress is not started a second time`() {
        val held = CountDownLatch(1)
        val starter = fakeStarter(beforeStart = { held.await(5, TimeUnit.SECONDS) })
        val lifecycle = lifecycle()

        lifecycle.ensureStarted(false)
        lifecycle.ensureStarted(false)
        lifecycle.ensureStarted(true)
        held.countDown()
        awaitRunning()
        flushOutbound(lifecycle)

        assertEquals(listOf<ServerState>(ServerState.Starting, ServerState.Running), stateNames())
        assertEquals(1, starter.processes.size)
    }

    @Test
    fun `a server without a command stays unavailable until asked to start even if refused`() {
        val starter = fakeStarter()
        val spec = TestSpec(command = null)
        val lifecycle = lifecycle(spec)

        lifecycle.ensureStarted(false)
        awaitState("unavailable") { it == ServerState.Unavailable }
        lifecycle.ensureStarted(false)
        spec.command = ServerCommand(Path.of("fake-server"))
        lifecycle.ensureStarted(false)
        assertEquals(listOf<ServerState>(ServerState.Starting, ServerState.Unavailable), stateNames())
        assertEquals(0, starter.processes.size)

        lifecycle.ensureStarted(true)
        awaitRunning()
        assertEquals(1, starter.processes.size)
        assertEquals(
            listOf("lsp test: no server available"),
            logs.messages(Level.INFO).filter { "no server" in it },
        )
    }

    @Test
    fun `a server the project trust refuses stays untrusted until asked to start even if refused`() {
        val starter = fakeStarter()
        val allowed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val lifecycle =
            lifecycle(
                TestSpec(runsProjectCode = true, trustCheckId = "chk"),
                trustGate = TrustGate { _, _ -> allowed.get() },
            )

        lifecycle.ensureStarted(false)
        awaitState("untrusted") { it == ServerState.Untrusted }
        allowed.set(true)
        lifecycle.ensureStarted(false)
        assertEquals(listOf<ServerState>(ServerState.Starting, ServerState.Untrusted), stateNames())
        assertEquals(0, starter.processes.size)

        lifecycle.ensureStarted(true)
        awaitRunning()
        assertEquals(1, starter.processes.size)
    }

    @Test
    fun `the trust checks are asked in order and the first refusal stops the start, naming it`() {
        val asked = CopyOnWriteArrayList<String>()
        val starter = fakeStarter()
        val lifecycle =
            lifecycle(
                TestSpec(runsProjectCode = true, trustChecks = listOf("chk1", "chk2", "chk3")),
                trustGate = TrustGate { id, _ -> asked.add(id) && id == "chk1" },
            )

        lifecycle.ensureStarted(false)
        awaitState("untrusted") { it == ServerState.Untrusted }

        assertEquals(listOf("chk1", "chk2"), asked)
        assertEquals(0, starter.processes.size)
        assertEquals(
            listOf("lsp test: not started, the project trust does not allow chk2"),
            logs.messages(Level.INFO).filter { "trust" in it },
        )
    }

    @Test
    fun `a start waits for a trust answer that comes later, and is not cancelled while it is current`() {
        val polls = AtomicInteger()
        val allow = CountDownLatch(1)
        val starter = fakeStarter()
        val lifecycle =
            lifecycle(
                TestSpec(runsProjectCode = true, trustCheckId = "chk"),
                trustGate =
                    TrustGate { _, cancelled ->
                        while (!allow.await(10, TimeUnit.MILLISECONDS)) {
                            polls.incrementAndGet()
                            if (cancelled()) return@TrustGate false
                        }
                        true
                    },
            )

        lifecycle.ensureStarted(false)
        awaitTrue("the gate polled the cancellation a few times") { polls.get() >= 3 }
        assertEquals(listOf<ServerState>(ServerState.Starting), stateNames())
        assertEquals(0, starter.processes.size)
        allow.countDown()

        awaitRunning()
        assertEquals(listOf<ServerState>(ServerState.Starting, ServerState.Running), stateNames())
    }

    @Test
    fun `a process that cannot be started is retried after 1, 4 and 15 seconds, then the server fails`() {
        val starter = fakeStarter(beforeStart = { throw IOException("boom") })
        val lifecycle = lifecycle()

        lifecycle.ensureStarted(false)
        awaitState("restart 1") { it == ServerState.Restarting(1) }
        val delays = mutableListOf(scheduler.runNext())
        awaitState("restart 2") { it == ServerState.Restarting(2) }
        delays += scheduler.runNext()
        awaitState("restart 3") { it == ServerState.Restarting(3) }
        delays += scheduler.runNext()
        awaitState("failed") { it is ServerState.Failed }

        assertEquals(listOf(1.seconds, 4.seconds, 15.seconds), delays)
        val reason = "Test language server could not be started: boom"
        assertEquals(
            listOf<ServerState>(
                ServerState.Starting,
                ServerState.Restarting(1),
                ServerState.Restarting(2),
                ServerState.Restarting(3),
                ServerState.Failed(reason),
            ),
            stateNames(),
        )
        assertEquals(
            listOf(
                "lsp test: $reason; restart 1 in 1s",
                "lsp test: $reason; restart 2 in 4s",
                "lsp test: $reason; restart 3 in 15s",
                "lsp test: $reason; not restarted again",
            ),
            logs.messages(Level.WARNING),
        )
        assertEquals(0, starter.processes.size)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unexpectedFailures")
    fun `a starter that throws fails the server at once, with the reason, and logs it as an error`(
        failure: RuntimeException,
        message: String,
    ) {
        starter = ServerStarter { _, _, _ -> throw failure }
        val lifecycle = lifecycle()

        lifecycle.ensureStarted(false)
        awaitState("failed") { it is ServerState.Failed }

        assertEquals(
            listOf<ServerState>(
                ServerState.Starting,
                ServerState.Failed("Test language server could not start: $message"),
            ),
            stateNames(),
        )
        assertEquals(listOf("lsp test: starting the server failed"), logs.messages(Level.SEVERE))
    }

    @Test
    fun `a server that refuses initialize counts as a crash, with its message`() {
        fakeStarter(
            newServer = {
                object : FakeServer() {
                    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
                        CompletableFuture.failedFuture(
                            ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "no way", null)),
                        )
                }
            },
        )

        lifecycle().ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }

        assertEquals(
            listOf("lsp test: Test language server refused initialize: no way; restart 1 in 1s"),
            logs.messages(Level.WARNING),
        )
        assertEquals(emptyList(), logs.messages(Level.SEVERE), "a crash during start is no failure of the start")
    }

    @Test
    fun `no answer to initialize within the timeout counts as a crash, naming the timeout`() {
        fakeStarter(newServer = { FakeServer().apply { answerInitialize = false } })

        val timing = ServerTiming(initialize = 300.milliseconds, shutdown = 200.milliseconds)
        lifecycle(timing = timing).ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }

        assertEquals(
            listOf("lsp test: Test language server did not answer initialize within 300ms; restart 1 in 1s"),
            logs.messages(Level.WARNING),
        )
        assertEquals(emptyList(), logs.messages(Level.SEVERE), "a crash during start is no failure of the start")
    }

    /** A process whose stdin throws on a write of a message that [breaks] says so. */
    private class BrokenStdin(
        private val process: FakeProcess,
        private val breaks: (String) -> Boolean,
    ) : ServerProcess by process {
        override val output: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = process.output.write(b)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (breaks(String(b, off, len, Charsets.UTF_8))) throw IOException("stdin is gone")
                    process.output.write(b, off, len)
                }

                override fun flush() = process.output.flush()
            }
    }

    private fun brokenStdinStarter(breaks: (String) -> Boolean) {
        val fake = fakeStarter()
        starter =
            ServerStarter { name, command, root ->
                BrokenStdin(fake.start(name, command, root) as FakeProcess, breaks)
            }
    }

    @Test
    fun `a server whose stdin breaks before initialize is sent counts as a crash`() {
        brokenStdinStarter { true }

        lifecycle().ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }

        val warning = logs.messages(Level.WARNING).single()
        assertTrue(warning.startsWith("lsp test: Test language server"), warning)
        assertTrue(warning.endsWith("; restart 1 in 1s"), warning)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unexpectedFailures")
    fun `a spec that throws while initialize is built counts as a crash with the exception's message`(
        failure: RuntimeException,
        message: String,
    ) {
        fakeStarter()
        val spec = adjusting { throw failure }

        lifecycle(spec).ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }

        assertEquals(listOf("lsp test: Test language server: $message; restart 1 in 1s"), logs.messages(Level.WARNING))
        assertEquals(emptyList(), logs.messages(Level.SEVERE))
    }

    @Test
    fun `initialization options that are no JSON are left out of initialize and logged`() {
        val starter = fakeStarter()

        started(lifecycle(TestSpec(initOptions = StringBuilder("not JSON"))))

        assertNull(
            starter.last.server.initializeParams!!
                .initializationOptions,
        )
        val warning = logs.messages(Level.WARNING).single()
        assertTrue(
            warning.startsWith("lsp test: initialization options left out: Not a JSON value: java.lang.StringBuilder"),
            warning,
        )
    }

    @Test
    fun `a running server is logged with the time it took to initialize and the server's name and version`() {
        val clock = AtomicLong(5_000_000_000)
        // Each reading of the clock is 1 ms later: the start, the moment it runs and the log line.
        val step = { clock.addAndGet(1_000_000) }
        fakeStarter(
            newServer = {
                object : FakeServer() {
                    override fun initialize(params: InitializeParams) =
                        super.initialize(params).thenApply { it.apply { serverInfo = ServerInfo("fake-ls", "1.2") } }
                }
            },
        )

        started(lifecycle(nanoTime = step))

        // Logged after Running is published.
        awaitTrue("the line was logged") { logs.messages(Level.INFO).any { "initialized in" in it } }
        val initialized = logs.messages(Level.INFO).single { "initialized in" in it }
        assertTrue(Regex("lsp test#\\d+ initialized in 2 ms \\(fake-ls 1\\.2\\)").matches(initialized), initialized)
    }

    @Test
    fun `a running server that names no version, or no server info at all, is logged without them`() {
        val clock = AtomicLong(0)
        val step = { clock.addAndGet(3_000_000) }
        fakeStarter(
            newServer = {
                object : FakeServer() {
                    override fun initialize(params: InitializeParams) =
                        super.initialize(params).thenApply { it.apply { serverInfo = ServerInfo("bare", null) } }
                }
            },
        )
        started(lifecycle(nanoTime = step))
        fakeStarter()
        val second = lifecycle(nanoTime = step)
        second.ensureStarted(false)
        awaitRunning(2)

        awaitTrue("both lines were logged") { logs.messages(Level.INFO).count { "initialized in" in it } == 2 }
        val lines = logs.messages(Level.INFO).filter { "initialized in" in it }
        assertEquals(2, lines.size)
        assertTrue(Regex("lsp test#\\d+ initialized in 6 ms \\(bare \\)").matches(lines[0]), lines[0])
        assertTrue(Regex("lsp test#\\d+ initialized in 6 ms").matches(lines[1]), lines[1])
    }

    @Test
    fun `after the close nothing starts and nothing more is published, however often it is asked`() {
        fakeStarter()
        val lifecycle = started(lifecycle())
        lifecycle.close()
        assertEquals(ServerState.Stopped, states.last().first)
        val closed = states.toList()

        lifecycle.close()
        lifecycle.ensureStarted(true)
        lifecycle.restart()

        assertEquals(closed, states.toList())
        assertTrue(lifecycle.isClosed)
    }

    @Test
    fun `closing cancels a restart that is due`() {
        fakeStarter(beforeStart = { throw IOException("boom") })
        val lifecycle = lifecycle()
        lifecycle.ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }
        awaitTrue("the restart is scheduled") { scheduler.due.size == 1 }

        lifecycle.close()

        assertEquals(emptyList(), scheduler.due)
    }

    @Test
    fun `closing a server that never started reports no failure`() {
        LogRecords(Logger.getLogger("dev.sluide.backgroundtasks.NamedThreads")).use { threadLogs ->
            val lifecycle = lifecycle()

            lifecycle.close()

            // A close thread that failed would log; there is nothing to wait for when nothing happens.
            Thread.sleep(300)
            assertEquals(emptyList(), threadLogs.messages())
        }
    }

    companion object {
        @JvmStatic
        fun unexpectedFailures() =
            listOf(
                Arguments.of(IllegalStateException("spec broke"), "spec broke"),
                Arguments.of(IllegalStateException(), "java.lang.IllegalStateException"),
            )
    }
}
