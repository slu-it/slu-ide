package dev.sluide.docker.tab

import dev.sluide.docker.DockerSession
import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.Volume
import dev.sluide.docker.model.container
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/*
 * The fixtures of the Docker tab's tests: a session over a FakeDockerCli ([fakeSession], [TabTestSession]), an FX
 * thread for unit tests ([FxQueue]), the FX thread of UI tests ([onFx], [awaitTrue]) and resources to list.
 */

/** The environment the fake session's CLI runs with. */
internal val FAKE_ENVIRONMENT = mapOf("DOCKER_HOST" to "unix:///run/fake.sock")

/**
 * A [DockerSession] over [cli] whose own work runs at once on the calling thread; [fx] is where its state and list
 * changes go: at once by default (unit tests), `Platform::runLater` in UI tests, as in the IDE. Advance [scheduler]
 * to let events reload the lists.
 */
internal fun fakeSession(
    cli: FakeDockerCli,
    scheduler: ManualScheduler = ManualScheduler(),
    fx: (Runnable) -> Unit = { it.run() },
): DockerSession =
    DockerSession(
        executable = SimpleStringProperty("docker"),
        connect = { DockerConnection.Ready(ResolvedDocker(Path.of("/usr/bin/docker"), FAKE_ENVIRONMENT), cli) },
        worker = { it.run() },
        scheduler = scheduler,
        fx = fx,
    )

/**
 * A subscribed [fakeSession] with one container, `id-web`, for the Logs and Stats views' UI tests: the lists reach the
 * FX thread as in the IDE, the streams open on their own threads (wait with [awaitTrue]).
 */
class TabTestSession(
    state: ContainerState = ContainerState.RUNNING,
) : AutoCloseable {
    val cli = FakeDockerCli()
    private val scheduler = ManualScheduler()
    internal val session = fakeSession(cli, scheduler, Platform::runLater)
    private val subscription: AutoCloseable

    init {
        cli.containers = listOf(web(state, "Up"))
        subscription = session.subscribe()
        awaitTrue("the container list") { onFx { session.containers.isNotEmpty() } }
    }

    private fun web(
        state: ContainerState,
        status: String,
    ): Container = container("web", state = state).copy(status = status)

    /** The container as it is now, read on the FX thread. */
    fun current(): Container = onFx { session.containers.single() }

    /** The container's new state and status, as the engine's event and the list reload bring them. */
    fun changeState(
        state: ContainerState,
        status: String = "Up",
    ) {
        cli.containers = listOf(web(state, status))
        cli.pushEvent(DockerEvent("container", "die", "id-web"))
        scheduler.advance(300.milliseconds)
        awaitTrue("the list shows $state, $status") {
            onFx { session.containers.singleOrNull()?.let { it.state == state && it.status == status } == true }
        }
    }

    override fun close() {
        subscription.close()
        session.close()
    }
}

/**
 * The FX thread of a unit test: what is handed to it queues until the test runs it with [runUntil] or [drain] on its
 * own thread.
 */
internal class FxQueue : (Runnable) -> Unit {
    private val queue = LinkedBlockingQueue<Runnable>()

    /** How many tasks were run. */
    @Volatile var ran = 0
        private set

    override fun invoke(task: Runnable) {
        queue.add(task)
    }

    /** Runs what is queued, waiting for more, until [condition] holds; fails after 5 s. */
    fun runUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Not reached within 5 s")
            queue.poll(10, TimeUnit.MILLISECONDS)?.let(::run)
        }
    }

    /** Runs what is queued now. */
    fun drain() {
        while (true) run(queue.poll() ?: return)
    }

    private fun run(task: Runnable) {
        ran++
        task.run()
    }
}

/** Runs [block] on the FX thread and returns its result. */
fun <T> onFx(block: () -> T): T {
    val result = CompletableFuture<T>()
    Platform.runLater {
        try {
            result.complete(block())
        } catch (t: Throwable) {
            result.completeExceptionally(t)
        }
    }
    return result.get(5, TimeUnit.SECONDS)
}

/** Polls [condition] until it holds, at most 5 s. */
fun awaitTrue(
    what: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
        Thread.sleep(10) // poll interval
    }
}

internal fun image(
    repository: String?,
    tag: String?,
    id: String = "sha256:" + "a".repeat(64),
    size: Long = 25_400_000,
): Image = Image(id, repository, tag, null, size, null, dangling = repository == null)

internal fun volume(
    name: String,
    anonymous: Boolean = false,
    inUse: Boolean? = null,
): Volume = Volume(name, "local", "/var/lib/docker/volumes/$name/_data", emptyMap(), anonymous, inUse)

internal fun network(
    name: String,
    id: String = "net-$name",
    driver: String = "bridge",
): Network = Network(id, name, driver, "local")
