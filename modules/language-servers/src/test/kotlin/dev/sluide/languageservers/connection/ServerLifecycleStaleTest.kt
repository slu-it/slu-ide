package dev.sluide.languageservers.connection

import dev.sluide.languageservers.FakeProcess
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.FakeStarter
import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.TestSpec
import dev.sluide.languageservers.awaitTrue
import dev.sluide.languageservers.process.ServerStarter
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a start, a retry or the end of a process does once a restart, a crash or the close has replaced it: every
 * event carries the generation of its start and is ignored when that is no longer the current one. A held process
 * ([FakeProcess.release]) ends, and so reports its exit, at the moment the test chooses.
 */
internal class ServerLifecycleStaleTest : ServerLifecycleTestBase() {
    private fun stateNames() = states.map { it.first }

    /** Holds the outbound thread, so that what is submitted next queues behind it; [release] lets it go. */
    private class Hold(
        lifecycle: ServerLifecycle,
    ) {
        private val release = CountDownLatch(1)

        init {
            val busy = CountDownLatch(1)
            lifecycle.onServer {
                busy.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            check(busy.await(5, TimeUnit.SECONDS))
        }

        fun release() = release.countDown()
    }

    @Test
    fun `a start that a restart replaced while it was queued starts nothing`() {
        val starter = fakeStarter()
        val asked = AtomicInteger()
        val lifecycle = lifecycle(projectFolder = { folder.also { asked.incrementAndGet() } })
        val hold = Hold(lifecycle)

        lifecycle.ensureStarted(false)
        lifecycle.restart()
        hold.release()
        awaitRunning()
        flushOutbound(lifecycle)

        assertEquals(1, starter.processes.size)
        assertEquals(1, asked.get(), "the replaced start did not even ask for the window folder")
        assertEquals(listOf<ServerState>(ServerState.Starting, ServerState.Starting, ServerState.Running), stateNames())
    }

    @Test
    fun `a start that a restart replaced while it asked for the window folder settles nothing`() {
        fakeStarter()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val asked = AtomicInteger()
        val lifecycle =
            lifecycle(
                projectFolder = {
                    if (asked.getAndIncrement() == 0) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        null
                    } else {
                        folder
                    }
                },
            )

        lifecycle.ensureStarted(false)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lifecycle.restart()
        release.countDown()
        awaitRunning()
        flushOutbound(lifecycle)

        assertEquals(listOf<ServerState>(ServerState.Starting, ServerState.Starting, ServerState.Running), stateNames())
    }

    @Test
    fun `a start waiting for the old process that a second restart replaced starts no process`() {
        val starter = fakeStarter(holdsEnd = { it == 0 })
        val lifecycle = started(lifecycle())

        lifecycle.restart()
        lifecycle.restart()
        awaitRunning(2)
        flushOutbound(lifecycle)

        assertEquals(2, starter.processes.size, "the first process and the one of the second restart")
    }

    @Test
    fun `a start replaced after initialized was sent does not become the running server`() {
        val fake = fakeStarter()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        starter =
            ServerStarter { name, command, root ->
                val process = fake.start(name, command, root) as FakeProcess
                if (fake.processes.size > 1) {
                    process
                } else {
                    HookedStdin(process) { written ->
                        if ("\"method\":\"initialized\"" in written) {
                            reached.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        val lifecycle = lifecycle()

        lifecycle.ensureStarted(false)
        assertTrue(reached.await(5, TimeUnit.SECONDS), "initialized is being written")
        lifecycle.restart()
        release.countDown()
        awaitTrue("the second process started") { fake.processes.size == 2 }
        awaitRunning()
        flushOutbound(lifecycle)

        assertEquals(listOf(ServerState.Running to 1), states.filter { it.first == ServerState.Running })
    }

    @Test
    fun `a start replaced while initialize is unanswered cancels the request at the server`() {
        val pending = CompletableFuture<InitializeResult>()
        val arrived = CountDownLatch(1)
        fakeStarter(
            newServer = { index ->
                if (index > 0) {
                    FakeServer()
                } else {
                    object : FakeServer() {
                        override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
                            calls += "initialize"
                            arrived.countDown()
                            return pending
                        }
                    }
                }
            },
            // Held, so that its streams are still open when the cancellation is sent.
            holdsEnd = { it == 0 },
        )
        val lifecycle = lifecycle()
        lifecycle.ensureStarted(false)
        assertTrue(arrived.await(5, TimeUnit.SECONDS), "initialize arrived")

        lifecycle.restart()

        awaitTrue("the request was cancelled at the server") { pending.isCancelled }
        awaitRunning()
    }

    @Test
    fun `the start after a crash waits until the killed process is gone, as long as the exit wait allows`() {
        val holder = AtomicReference<FakeStarter>()
        val firstEnded = AtomicReference<Boolean>()
        val timing = ServerTiming(initialize = 300.milliseconds, shutdown = 200.milliseconds, exit = 2_000.milliseconds)
        holder.set(
            fakeStarter(
                newServer = { index -> FakeServer().apply { answerInitialize = index > 0 } },
                beforeStart = { index ->
                    if (index == 1) {
                        firstEnded.set(
                            holder
                                .get()
                                .processes
                                .first()
                                .isEnded,
                        )
                    }
                },
                holdsEnd = { it == 0 },
            ),
        )
        val lifecycle = lifecycle(timing = timing)

        lifecycle.ensureStarted(false)
        awaitTrue("the first process started") { holder.get().processes.size == 1 }
        val first = holder.get().processes.first()
        thread(isDaemon = true) {
            if (first.awaitEndAsked()) {
                // The moment a killed process takes to die; the check below waits for the end, not for this.
                Thread.sleep(200)
                first.release()
            }
        }
        awaitState("restarting") { it == ServerState.Restarting(1) }
        scheduler.runNext()
        awaitRunning()

        assertEquals(true, firstEnded.get(), "the second start waited until the first process was gone")
    }

    @Test
    fun `a restart that was due when a restart replaced it does nothing, and keeps the next one cancellable`() {
        val fake = fakeStarter(beforeStart = { if (it == 0) throw IOException("boom") })
        val lifecycle = lifecycle()
        lifecycle.ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }
        awaitTrue("the retry is scheduled") { scheduler.due.size == 1 }
        val stale = scheduler.due.first().second
        lifecycle.restart()
        awaitRunning()
        fake.last.crash()
        awaitTrue("the next retry is scheduled") {
            states.count { it.first == ServerState.Restarting(1) } == 2 && scheduler.due.size == 1
        }

        stale.run()
        lifecycle.close()

        assertEquals(emptyList(), scheduler.due)
    }

    @Test
    fun `a restart that was due before a detour through unavailable does not start a second server`() {
        val fake = fakeStarter(beforeStart = { if (it == 0) throw IOException("boom") })
        val spec = TestSpec()
        val lifecycle = lifecycle(spec)
        lifecycle.ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }
        awaitTrue("the retry is scheduled") { scheduler.due.size == 1 }
        val stale = scheduler.due.first().second
        // Each step moves the generation on; a stale retry must not find its number again on the way back.
        val command = spec.command
        spec.command = null
        lifecycle.restart()
        awaitState("unavailable") { it == ServerState.Unavailable }
        spec.command = command
        lifecycle.ensureStarted(true)
        awaitRunning()

        stale.run()
        flushOutbound(lifecycle)
        flushOutbound(lifecycle)

        assertEquals(1, states.count { it.first == ServerState.Running })
        assertEquals(1, fake.processes.size)
    }

    @Test
    fun `the exit of a process that crashed during start is ignored after a restart`() {
        val fake = fakeStarter(newServer = { FakeServer().apply { answerInitialize = it > 0 } }, holdsEnd = { it == 0 })
        val timing = ServerTiming(initialize = 300.milliseconds, shutdown = 200.milliseconds, exit = 200.milliseconds)
        val lifecycle = lifecycle(timing = timing)
        lifecycle.ensureStarted(false)
        awaitState("restarting") { it == ServerState.Restarting(1) }
        lifecycle.restart()
        awaitRunning()

        fake.processes.first().release()

        assertTrue(fake.processes.first().awaitEndHandled(), "the exit was handled")
        assertEquals(ServerState.Running, states.last().first)
    }

    @Test
    fun `the exit of a process of a replaced server is ignored once a later server crashed`() {
        val fake = fakeStarter(holdsEnd = { it == 0 })
        val lifecycle = started(lifecycle())
        lifecycle.restart()
        awaitRunning(2)
        fake.last.crash()
        awaitState("restarting") { it == ServerState.Restarting(1) }

        fake.processes.first().release()

        assertTrue(fake.processes.first().awaitEndHandled(), "the exit was handled")
        assertEquals(
            listOf<ServerState>(
                ServerState.Starting,
                ServerState.Running,
                ServerState.Starting,
                ServerState.Running,
                ServerState.Restarting(1),
            ),
            stateNames(),
        )
    }

    @Test
    fun `the exit of a process of a start that failed is ignored after a restart`() {
        val fake = fakeStarter(holdsEnd = { it == 0 })
        val asked = AtomicInteger()
        val lifecycle =
            lifecycle(
                workspaceFolders = { root ->
                    if (asked.getAndIncrement() == 0) throw IllegalStateException("bad folders") else listOf(root)
                },
            )
        lifecycle.ensureStarted(false)
        awaitState("failed") { it is ServerState.Failed }
        lifecycle.restart()
        awaitRunning()

        fake.processes.first().release()

        assertTrue(fake.processes.first().awaitEndHandled(), "the exit was handled")
        assertEquals(ServerState.Running, states.last().first)
        assertEquals(
            ServerState.Failed("Test language server could not start: bad folders"),
            states.map { it.first }.single { it is ServerState.Failed },
        )
    }

    @Test
    fun `a start that fails after a restart replaced it fails nothing`() {
        val held = CountDownLatch(1)
        val reached = CountDownLatch(1)
        val fake = fakeStarter()
        val calls = AtomicInteger()
        starter =
            ServerStarter { name, command, root ->
                if (calls.getAndIncrement() == 0) {
                    reached.countDown()
                    held.await(5, TimeUnit.SECONDS)
                    throw IllegalStateException("too late")
                }
                fake.start(name, command, root)
            }
        val lifecycle = lifecycle()
        lifecycle.ensureStarted(false)
        assertTrue(reached.await(5, TimeUnit.SECONDS))

        lifecycle.restart()
        held.countDown()
        awaitRunning()
        flushOutbound(lifecycle)

        assertEquals(listOf<ServerState>(ServerState.Starting, ServerState.Starting, ServerState.Running), stateNames())
    }

    @Test
    fun `a server that needs a moment to exit after exit is not killed by a restart`() {
        val fake = fakeStarter(holdsEnd = { it == 0 })
        val lifecycle =
            started(lifecycle(timing = ServerTiming(shutdown = 500.milliseconds, exit = 2_000.milliseconds)))
        val first = fake.last
        thread(isDaemon = true) {
            if (first.awaitEndAsked()) {
                // The moment a process takes to leave after exit; the check below waits for the end, not for this.
                Thread.sleep(200)
                first.release()
            }
        }

        lifecycle.restart()
        awaitRunning(2)

        assertTrue(first.isEnded)
        assertEquals(false, first.destroyed, "it was given the exit wait and left on its own")
    }
}
