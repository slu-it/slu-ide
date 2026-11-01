package dev.sluide.docker

import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.Volume
import javafx.beans.property.SimpleStringProperty
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [DockerSession] with a [FakeDockerCli], a hand-moved clock and its work and FX hand-offs run at once on the test's
 * thread, so every step is synchronous.
 */
class DockerSessionTest {
    private val cli = FakeDockerCli()
    private val scheduler = ManualScheduler()
    private val setting = SimpleStringProperty("docker")
    private val connects = mutableListOf<String>()
    private var notFound: String? = null
    private val session = session()

    /** A session over [cli]; [worker] and [fx] run at once unless a test queues them. */
    private fun session(
        worker: (Runnable) -> Unit = { it.run() },
        fx: (Runnable) -> Unit = { it.run() },
    ) = DockerSession(
        executable = setting,
        connect = { value ->
            connects += value
            notFound?.let { DockerConnection.NotFound(it) }
                ?: DockerConnection.Ready(ResolvedDocker(Path.of("/usr/bin/$value"), emptyMap()), cli)
        },
        worker = worker,
        scheduler = scheduler,
        fx = fx,
    )

    @AfterTest
    fun closeSession() = session.close()

    private fun container(name: String) =
        Container(
            id = "id-$name",
            name = name,
            image = "bash:5.2",
            imageId = null,
            state = ContainerState.RUNNING,
            status = "Up",
            ports = emptyList(),
            labels = emptyMap(),
            createdAt = null,
            volumes = emptyList(),
            networks = emptyList(),
        )

    private fun loads() = cli.calls.filter { it in setOf("containers", "images", "volumes", "networks") }

    @Test
    fun `nothing runs before the first subscription`() {
        assertEquals(EngineState.Idle, session.state.get())
        assertEquals(emptyList(), connects)
        assertEquals(emptyList(), cli.calls)
    }

    @Test
    fun `the first subscription connects, loads every list and starts one events stream`() {
        cli.containers = listOf(container("web"))
        cli.volumes = listOf(Volume("data", "local", null, emptyMap(), anonymous = false))

        session.subscribe()
        session.subscribe()

        assertEquals(listOf("docker"), connects)
        assertEquals(listOf("engine", "events", "containers", "images", "volumes", "networks"), cli.calls)
        assertEquals(EngineState.Available(cli.engine), session.state.get())
        assertEquals(listOf("web"), session.containers.map { it.name })
        assertEquals(listOf("data"), session.volumes.map { it.name })
        assertEquals(Path.of("/usr/bin/docker"), session.docker?.executable)
    }

    @Test
    fun `an event reloads only the lists it concerns, once per burst, after the debounce`() {
        session.subscribe()
        cli.calls.clear()
        cli.containers = listOf(container("web"))

        cli.pushEvent(DockerEvent("container", "start", "id-web"))
        cli.pushEvent(DockerEvent("container", "die", "id-web"))
        cli.pushEvent(DockerEvent("network", "create", "n1"))
        cli.pushEvent(DockerEvent("container", "exec_start: sh", "id-web"))
        scheduler.advance(199.milliseconds)
        assertEquals(emptyList(), loads(), "not before the debounce is over")

        scheduler.advance(1.milliseconds)

        assertEquals(listOf("containers", "networks"), loads())
        assertEquals(listOf("web"), session.containers.map { it.name })
    }

    @Test
    fun `when the stream fails the engine is unavailable, retried with a growing wait, and reloaded when back`() {
        session.subscribe()
        cli.failing = { if (it == "engine") DockerException("Cannot connect to the Docker daemon") else null }

        cli.endEvents(DockerException("error during connect"))

        assertEquals(EngineState.Unavailable("error during connect"), session.state.get())
        assertEquals(listOf(2.seconds), scheduler.pending)
        scheduler.advance(2.seconds)
        assertEquals(EngineState.Unavailable("Cannot connect to the Docker daemon"), session.state.get())
        assertEquals(listOf(4.seconds), scheduler.pending)
        scheduler.advance(4.seconds)
        scheduler.advance(8.seconds)
        scheduler.advance(16.seconds)
        assertEquals(listOf(30.seconds), scheduler.pending, "at most 30 s")

        cli.failing = { null }
        cli.calls.clear()
        cli.networks = listOf(Network("n1", "shop_default", "bridge", "local"))
        scheduler.advance(30.seconds)

        assertEquals(EngineState.Available(cli.engine), session.state.get())
        assertEquals(listOf("engine", "events", "containers", "images", "volumes", "networks"), cli.calls)
        assertEquals(listOf("shop_default"), session.networks.map { it.name })
        assertEquals(1, cli.eventStreams.count { !it.closed && !it.ended }, "one stream again")
        assertEquals(emptyList(), scheduler.pending)
        cli.endEvents()
        assertEquals(listOf(2.seconds), scheduler.pending, "the wait starts over after a success")
    }

    @Test
    fun `events from the start of the initial load are not lost`() {
        cli.failing = { call ->
            // An event arrives while the lists load.
            if (call == "images") cli.pushEvent(DockerEvent("container", "start", "id-web"))
            null
        }
        session.subscribe()
        cli.failing = { null }
        cli.calls.clear()

        scheduler.advance(200.milliseconds)

        assertEquals(listOf("containers"), loads())
    }

    @Test
    fun `a stream that ends without an error is an outage too`() {
        session.subscribe()

        cli.endEvents(null)

        assertIs<EngineState.Unavailable>(session.state.get())
        assertEquals(listOf(2.seconds), scheduler.pending)
    }

    @Test
    fun `the last subscription closing stops the stream and keeps the lists`() {
        cli.containers = listOf(container("web"))
        val first = session.subscribe()
        val second = session.subscribe()

        first.close()
        first.close()
        assertTrue(!cli.eventStreams.single().closed, "still subscribed")
        second.close()

        assertTrue(cli.eventStreams.single().closed)
        assertEquals(listOf("web"), session.containers.map { it.name })
    }

    @Test
    fun `the last subscription closing during an outage cancels the pending retry`() {
        val subscription = session.subscribe()
        cli.failing = { if (it == "engine") DockerException("engine gone") else null }
        cli.endEvents()
        assertEquals(listOf(2.seconds), scheduler.pending)
        cli.calls.clear()

        subscription.close()

        assertEquals(emptyList(), scheduler.pending)
        scheduler.advance(3.seconds)
        assertEquals(emptyList(), cli.calls, "no retry ran")
    }

    @Test
    fun `closing the session during an outage cancels the pending retry`() {
        session.subscribe()
        cli.failing = { if (it == "engine") DockerException("engine gone") else null }
        cli.endEvents()
        cli.calls.clear()

        session.close()

        assertEquals(emptyList(), scheduler.pending)
        scheduler.advance(3.seconds)
        assertEquals(emptyList(), cli.calls, "no retry ran")
    }

    @Test
    fun `a subscription ending and a new one starting during an outage leave one retry`() {
        // The worker queues: the unsubscription's work runs only after the new subscription counted.
        val queued = ArrayDeque<Runnable>()
        val queuing = session(worker = { queued.addLast(it) })

        fun drain() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
        cli.failing = { if (it == "engine") DockerException("engine gone") else null }
        val first = queuing.subscribe()
        drain()
        assertEquals(listOf(2.seconds), scheduler.pending)

        first.close()
        val second = queuing.subscribe()
        drain()

        assertEquals(1, scheduler.pending.size, "pending: ${scheduler.pending}")
        cli.calls.clear()
        scheduler.advance(30.seconds)
        drain()
        assertEquals(1, cli.count("engine"), "one retry ran: ${cli.calls}")
        second.close()
        queuing.close()
    }

    @Test
    fun `an executable that is not found is reported, not retried, and looked up again on refresh`() {
        notFound = "docker was not found on the PATH of your login shell."
        val subscription = session.subscribe()

        assertEquals(EngineState.NotFound("docker was not found on the PATH of your login shell."), session.state.get())
        assertEquals(emptyList(), scheduler.pending)
        assertEquals(emptyList(), cli.calls)

        notFound = null
        session.refresh()

        assertEquals(EngineState.Available(cli.engine), session.state.get())
        assertEquals(listOf("docker", "docker"), connects)
        assertEquals(1, cli.count("events"))
        subscription.close()
    }

    @Test
    fun `a changed executable connects again at once while subscribed, else on the next subscription`() {
        session.subscribe().close()

        setting.set("podman")
        assertEquals(listOf("docker"), connects, "nobody subscribes")

        session.subscribe()
        assertEquals(listOf("docker", "podman"), connects)
        setting.set("/opt/podman/bin/podman")

        assertEquals(listOf("docker", "podman", "/opt/podman/bin/podman"), connects)
        assertEquals(1, cli.eventStreams.count { !it.closed }, "the old stream was closed")
    }

    @Test
    fun `refresh reloads everything without connecting again, and shows Checking only when not available`() {
        val states = mutableListOf<EngineState>()
        session.state.subscribe { state -> states += state }
        session.subscribe()
        cli.calls.clear()

        session.refresh()

        assertEquals(listOf("engine", "containers", "images", "volumes", "networks"), cli.calls)
        assertEquals(listOf("docker"), connects)
        assertEquals(
            listOf(EngineState.Idle, EngineState.Checking, EngineState.Available(cli.engine)),
            states.distinct(),
        )
    }

    @Test
    fun `a failing reload is an outage`() {
        session.subscribe()
        cli.failing = { if (it == "images") DockerException("engine gone") else null }

        cli.pushEvent(DockerEvent("image", "pull", "x"))
        scheduler.advance(200.milliseconds)

        assertEquals(EngineState.Unavailable("engine gone"), session.state.get())
        assertTrue(cli.eventStreams.single().closed)
    }

    @Test
    fun `actions run with the CLI and fail with its error, or when Docker was not found`() {
        assertFailsWith<ExecutionException> { session.submit("start") { it.engine() }.get(5, TimeUnit.SECONDS) }

        session.subscribe()
        session.submit("stop") { it.act(dev.sluide.docker.model.ContainerAction.STOP, "web") }.get(5, TimeUnit.SECONDS)
        cli.failing = { if (it.startsWith("removeVolume")) DockerException("volume is in use", 1) else null }
        val failure =
            assertFailsWith<ExecutionException> {
                session.submit("remove") { it.removeVolume("data") }.get(5, TimeUnit.SECONDS)
            }

        assertTrue("act STOP web" in cli.calls)
        assertEquals("volume is in use", failure.cause?.message)
    }

    @Test
    fun `cancelling an action's future interrupts its thread and completes it as cancelled`() {
        session.subscribe()
        val running = CountDownLatch(1)
        val interrupted = CompletableFuture<Boolean>()

        val future =
            session.submit("pull") {
                running.countDown()
                try {
                    // Blocks like a pull that never ends, until the thread is interrupted.
                    CountDownLatch(1).await()
                    "pulled"
                } catch (_: InterruptedException) {
                    interrupted.complete(true)
                    throw DockerException("docker pull was interrupted")
                }
            }
        assertTrue(running.await(5, TimeUnit.SECONDS))

        assertTrue(future.cancel(true))

        assertTrue(future.isCancelled)
        assertFailsWith<CancellationException> { future.get(5, TimeUnit.SECONDS) }
        assertTrue(interrupted.get(5, TimeUnit.SECONDS), "the action's thread was interrupted")
    }

    @Test
    fun `cancelling without interrupting lets the action finish, also through a later interrupting cancel`() {
        session.subscribe()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableFuture<String>()

        val future =
            session.submit("stop") {
                running.countDown()
                release.await(5, TimeUnit.SECONDS)
                "stopped".also { finished.complete(it) }
            }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        assertTrue(future.cancel(false))
        // Answers true like the JDK, but must not interrupt the action the first cancel let run.
        future.cancel(true)
        release.countDown()

        assertEquals("stopped", finished.get(5, TimeUnit.SECONDS), "not interrupted")
        assertTrue(future.isCancelled)
        val done = session.submit("start") { "started" }
        assertEquals("started", done.get(5, TimeUnit.SECONDS))
        assertFalse(done.cancel(true))
    }

    @Test
    fun `a stream opened through the session closes with its handle, also when closed before it was open`() {
        session.subscribe()
        val failures = mutableListOf<Throwable>()

        val handle = session.openStream("logs", { it.logs("web", 10, {}, {}) }, { failures += it })
        waitFor { cli.logStreams.size == 1 }
        handle.close()
        assertTrue(cli.logStreams.single().closed)

        val stats = session.openStream("stats", { it.stats("web", {}, {}) }, { failures += it })
        session.close()
        waitFor { cli.statsStreams.size == 1 }
        waitFor { cli.statsStreams.single().closed }
        stats.close()
        assertEquals(emptyList(), failures)
        assertFailsWith<ExecutionException> { session.submit("late") { 1 }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `a stream that cannot be opened reports the failure through the FX hand-off, after openStream returned`() {
        val handedOff = ArrayDeque<Runnable>()
        val deferred = session(fx = { handedOff.addLast(it) })
        val failures = mutableListOf<Throwable>()

        deferred.openStream("logs", { it.logs("web", 10, {}, {}) }, { failures += it })

        assertEquals(emptyList(), failures, "not on the caller's thread")
        while (handedOff.isNotEmpty()) handedOff.removeFirst().run()
        assertEquals(listOf("Docker is not available"), failures.map { it.message })
        deferred.close()
    }

    @Test
    fun `retry waits double from the first up to the most`() {
        assertEquals(
            listOf(2, 4, 8, 16, 30, 30),
            (0..5).map { retryDelay(it, 2.seconds, 30.seconds).inWholeSeconds.toInt() },
        )
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), "condition not met in time")
    }
}
