package dev.sluide.docker.cli

import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.EngineFlavor
import dev.sluide.docker.model.EngineInfo
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.model.StatsSample
import dev.sluide.docker.model.Volume
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [DockerCli] for tests: the lists and answers are settable, every call is recorded in [calls] (e.g. `containers`,
 * `act STOP web`, `removeContainer web volumes=true force=false`), [failing] makes calls throw, and the streams are
 * [FakeStream]s the test drives. Thread-safe; callbacks run on the thread that drives the stream.
 */
class FakeDockerCli : DockerCli {
    @Volatile var engine = EngineInfo(EngineFlavor.DOCKER, "29.8.1", "29.8.1", "Docker Engine - Community", "default")

    @Volatile var containers: List<Container> = emptyList()

    @Volatile var images: List<Image> = emptyList()

    @Volatile var volumes: List<Volume> = emptyList()

    @Volatile var networks: List<Network> = emptyList()

    /** The details [containerDetails] answers, by id. */
    @Volatile var details: Map<String, ContainerDetails> = emptyMap()

    /** What [inspect] answers. */
    @Volatile var inspectJson: (ResourceKind, String) -> String = { _, id -> "{\n  \"Id\": \"$id\"\n}" }

    /** What [composeConfig] answers. */
    @Volatile var composeConfigOutput = ComposeConfigOutput("{}", "")

    /** The arguments of every [composeConfig] call: the folder, the compose arguments and the environment. */
    val composeConfigCalls: MutableList<Triple<Path, List<String>, Map<String, String>>> = CopyOnWriteArrayList()

    /** What [pull] reports as progress. */
    @Volatile var pullProgress: List<String> = listOf("Pulling", "Done")

    /**
     * Makes calls fail: given the call as recorded in [calls], the exception to throw, or `null` to answer. E.g.
     * `failing = { if (it == "engine") DockerException("Cannot connect") else null }`.
     */
    @Volatile var failing: (String) -> DockerException? = { null }

    /** Every call, in order. */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** The events streams opened, in order; the last one is the current one. */
    val eventStreams: MutableList<FakeStream<DockerEvent>> = CopyOnWriteArrayList()

    /** The logs streams opened, in order. */
    val logStreams: MutableList<FakeStream<String>> = CopyOnWriteArrayList()

    /** The stats streams opened, in order. */
    val statsStreams: MutableList<FakeStream<StatsSample>> = CopyOnWriteArrayList()

    /** How often [call] was made. */
    fun count(call: String): Int = calls.count { it == call }

    /** Sends [event] on the last events stream that is still open. */
    fun pushEvent(event: DockerEvent) = openEvents().emit(event)

    /** Ends the last events stream that is still open, with [failure] (or normally). */
    fun endEvents(failure: Throwable? = DockerException("events ended")) = openEvents().end(failure)

    private fun openEvents() = eventStreams.last { !it.closed && !it.ended }

    private fun record(call: String) {
        calls += call
        failing(call)?.let { throw it }
    }

    override fun engine(): EngineInfo {
        record("engine")
        return engine
    }

    override fun containers(): List<Container> {
        record("containers")
        return containers
    }

    override fun images(): List<Image> {
        record("images")
        return images
    }

    override fun volumes(): List<Volume> {
        record("volumes")
        return volumes
    }

    override fun networks(): List<Network> {
        record("networks")
        return networks
    }

    override fun containerDetails(id: String): ContainerDetails {
        record("containerDetails $id")
        return details[id] ?: throw DockerException("Error: No such container: $id", 1)
    }

    override fun inspect(
        kind: ResourceKind,
        id: String,
    ): String {
        record("inspect $kind $id")
        return inspectJson(kind, id)
    }

    override fun act(
        action: ContainerAction,
        id: String,
    ) = record("act $action $id")

    override fun removeContainer(
        id: String,
        volumes: Boolean,
        force: Boolean,
    ) = record("removeContainer $id volumes=$volumes force=$force")

    override fun removeImage(
        reference: String,
        force: Boolean,
    ) = record("removeImage $reference force=$force")

    override fun removeVolume(name: String) = record("removeVolume $name")

    override fun removeNetwork(id: String) = record("removeNetwork $id")

    override fun pruneImages(): String {
        record("pruneImages")
        return "Total reclaimed space: 0B"
    }

    override fun pruneVolumes(): String {
        record("pruneVolumes")
        return "Total reclaimed space: 0B"
    }

    override fun pruneNetworks(): String {
        record("pruneNetworks")
        return ""
    }

    override fun pull(
        reference: String,
        onProgress: (String) -> Unit,
    ) {
        record("pull $reference")
        pullProgress.forEach(onProgress)
    }

    override fun createVolume(name: String) = record("createVolume $name")

    override fun createNetwork(
        name: String,
        driver: String?,
    ) = record("createNetwork $name driver=$driver")

    override fun composeConfig(
        workingDir: Path,
        composeArgs: List<String>,
        environment: Map<String, String>,
    ): ComposeConfigOutput {
        composeConfigCalls += Triple(workingDir, composeArgs, environment)
        record("composeConfig ${composeArgs.joinToString(" ")}")
        return composeConfigOutput
    }

    override fun events(
        onEvent: (DockerEvent) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable {
        record("events")
        return FakeStream(onEvent, onEnd).also { eventStreams += it }
    }

    override fun logs(
        id: String,
        tail: Int?,
        onLine: (String) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable {
        record("logs $id tail=$tail")
        return FakeStream(onLine, onEnd).also { logStreams += it }
    }

    override fun stats(
        id: String,
        onSample: (StatsSample) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable {
        record("stats $id")
        return FakeStream(onSample, onEnd).also { statsStreams += it }
    }
}

/**
 * A stream of [FakeDockerCli] that the test drives: [emit] delivers an item, [end] ends it as the CLI would. Like the
 * real streams, nothing is delivered once it is [closed] or [ended].
 */
class FakeStream<T>(
    private val onItem: (T) -> Unit,
    private val onEnd: (Throwable?) -> Unit,
) : AutoCloseable {
    @Volatile var closed = false
        private set

    @Volatile var ended = false
        private set

    fun emit(item: T) {
        if (!closed && !ended) onItem(item)
    }

    fun end(failure: Throwable? = null) {
        if (closed || ended) return
        ended = true
        onEnd(failure)
    }

    override fun close() {
        closed = true
    }
}
