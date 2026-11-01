package dev.sluide.docker.tab

import dev.sluide.docker.DockerSession
import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.container
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * A subscribed [DockerSession] on a [FakeDockerCli] with one container, `id-web`; its work runs at once on the caller's
 * thread, the lists reach the FX thread as in the IDE, the streams open on their own threads (wait with [awaitTrue]).
 */
class TabTestSession(
    state: ContainerState = ContainerState.RUNNING,
) : AutoCloseable {
    val cli = FakeDockerCli()
    private val scheduler = ManualScheduler()
    internal val session =
        DockerSession(
            executable = SimpleStringProperty("docker"),
            connect = { DockerConnection.Ready(ResolvedDocker(Path.of("/usr/bin/docker"), emptyMap()), cli) },
            worker = { it.run() },
            scheduler = scheduler,
        )
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
