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
import tools.jackson.core.JacksonException
import java.io.Reader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger(ProcessDockerCli::class.java.name)

/**
 * [DockerCli] over the executable of [docker], run directly with its environment, without a shell (ADR 0117): one
 * process per call, JSON output (`--format json`) parsed for Docker and Podman alike. Thread-safe.
 *
 * @param readTimeout how long listings and inspects may take.
 * @param writeTimeout how long actions, removals and creations may take (a stop waits 10 s for the container).
 * Pulls and prunes have no limit.
 */
internal class ProcessDockerCli(
    private val docker: ResolvedDocker,
    private val readTimeout: Duration = 30.seconds,
    private val writeTimeout: Duration = 2.minutes,
) : DockerCli {
    private val processes = DockerProcesses(docker.executable, docker.environment)

    /** The CLI's flavor: guessed from the executable's name until [engine] tells. */
    @Volatile private var flavor: EngineFlavor = docker.flavor

    override fun engine(): EngineInfo {
        val info = parsing("version") { parseEngine(read("version", "--format", "json")) }
        flavor = info.flavor
        if (info.flavor == EngineFlavor.PODMAN || info.context != null) return info
        val context =
            try {
                read("context", "show").trim().ifEmpty { null }
            } catch (e: DockerException) {
                log.log(System.Logger.Level.DEBUG, "No Docker context: ${e.message}")
                null
            }
        return info.copy(context = context)
    }

    override fun containers(): List<Container> =
        parsing("ps") { parseContainers(read("ps", "-a", "--no-trunc", "--format", "json")) }

    override fun images(): List<Image> =
        parsing("images") {
            mergeImages(
                parseImages(read("images", "--no-trunc", "--digests", "--format", "json")),
                parseImages(read("images", "--no-trunc", "--digests", "--filter", "dangling=true", "--format", "json")),
            )
        }

    override fun volumes(): List<Volume> {
        val listed = read("volume", "ls", "--format", "json")
        val dangling =
            try {
                read(
                    "volume",
                    "ls",
                    "-q",
                    "--filter",
                    "dangling=true",
                ).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } catch (e: DockerException) {
                log.log(System.Logger.Level.DEBUG, "Unknown which volumes are in use: ${e.message}")
                null
            }
        return parsing("volume ls") { parseVolumes(listed, dangling) }
    }

    override fun networks(): List<Network> =
        parsing("network ls") { parseNetworks(read("network", "ls", "--no-trunc", "--format", "json")) }

    override fun containerDetails(id: String): ContainerDetails =
        parsing("container inspect") { parseContainerDetails(read("container", "inspect", positional(id))) }

    override fun inspect(
        kind: ResourceKind,
        id: String,
    ): String = prettyInspect(read(kind.command, "inspect", positional(id)))

    override fun act(
        action: ContainerAction,
        id: String,
    ) {
        write(action.command, positional(id))
    }

    override fun removeContainer(
        id: String,
        volumes: Boolean,
        force: Boolean,
    ) {
        write(*listOfNotNull("rm", "-v".takeIf { volumes }, "-f".takeIf { force }, positional(id)).toTypedArray())
    }

    override fun removeImage(
        reference: String,
        force: Boolean,
    ) {
        write(*listOfNotNull("rmi", "-f".takeIf { force }, positional(reference)).toTypedArray())
    }

    override fun removeVolume(name: String) {
        write("volume", "rm", positional(name))
    }

    override fun removeNetwork(id: String) {
        write("network", "rm", positional(id))
    }

    override fun pruneImages(): String = long("image", "prune", "-f").trim()

    override fun pruneVolumes(): String =
        if (flavor == EngineFlavor.PODMAN) {
            // Podman's prune removes every unused volume.
            long("volume", "prune", "-f").trim()
        } else {
            // Docker 23 and later remove only anonymous volumes without --all.
            long("volume", "prune", "-a", "-f").trim()
        }

    override fun pruneNetworks(): String = long("network", "prune", "-f").trim()

    override fun pull(
        reference: String,
        onProgress: (String) -> Unit,
    ) {
        processes.run(listOf("pull", positional(reference)), CommandKind.WRITE, Duration.INFINITE, onProgress)
    }

    override fun createVolume(name: String) {
        write("volume", "create", positional(name))
    }

    override fun createNetwork(
        name: String,
        driver: String?,
    ) {
        val options = driver?.takeIf { it.isNotBlank() }?.let { listOf("--driver", positional(it)) } ?: emptyList()
        write(*(listOf("network", "create") + options + positional(name)).toTypedArray())
    }

    override fun events(
        onEvent: (DockerEvent) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable =
        processes.stream(
            listOf("events", "--format", "json"),
            mergeErrors = false,
            consume = { reader, open ->
                readObjects(reader, open) { node -> parseEvent(node)?.let { if (open()) onEvent(it) } }
            },
            onEnd = onEnd,
        )

    override fun logs(
        id: String,
        tail: Int?,
        onLine: (String) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable {
        val tailOptions = tail?.let { listOf("--tail", it.coerceAtLeast(0).toString()) } ?: emptyList()
        return processes.stream(
            listOf("logs", "-f", "--timestamps") + tailOptions + positional(id),
            mergeErrors = true,
            consume = { reader, open ->
                reader
                    .buffered()
                    .lineSequence()
                    .takeWhile { open() }
                    .forEach { line -> if (open()) onLine(line) }
            },
            onEnd = onEnd,
        )
    }

    override fun stats(
        id: String,
        onSample: (StatsSample) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable =
        processes.stream(
            listOf("stats", "--format", "json", positional(id)),
            mergeErrors = false,
            consume = { reader, open ->
                readObjects(reader, open) { node -> parseStats(node)?.let { if (open()) onSample(it) } }
            },
            onEnd = onEnd,
        )

    private fun read(vararg arguments: String) = processes.run(arguments.toList(), CommandKind.READ, readTimeout)

    private fun write(vararg arguments: String) = processes.run(arguments.toList(), CommandKind.WRITE, writeTimeout)

    private fun long(vararg arguments: String) = processes.run(arguments.toList(), CommandKind.WRITE, Duration.INFINITE)
}

/**
 * Runs [parse] over the CLI's output of [command]: an output of an unexpected shape fails as a [DockerException]
 * like the CLI's own errors, not as Jackson's.
 */
private fun <T> parsing(
    command: String,
    parse: () -> T,
): T =
    try {
        parse()
    } catch (e: JacksonException) {
        throw DockerException("Unexpected output of $command: ${e.originalMessage}", cause = e)
    }

/** Feeds the JSON objects of [reader] to [onObject] as they come, while [open]; objects of other shapes are skipped. */
private fun readObjects(
    reader: Reader,
    open: () -> Boolean,
    onObject: (tools.jackson.databind.JsonNode) -> Unit,
) {
    val splitter =
        JsonValueSplitter { value ->
            objectsOf(value).forEach { node ->
                try {
                    onObject(node)
                } catch (e: JacksonException) {
                    log.log(
                        System.Logger.Level.DEBUG,
                        "Skipped a stream object of an unexpected shape: ${e.originalMessage}",
                    )
                }
            }
        }
    val buffer = CharArray(8192)
    while (open()) {
        val read = reader.read(buffer)
        if (read < 0) return
        for (i in 0 until read) splitter.feed(buffer[i])
    }
}

/** [value] as a positional argument; one starting with `-` would be read as an option, so it is refused. */
private fun positional(value: String): String {
    if (value.isBlank() || value.startsWith("-")) throw DockerException("Not a valid name or id: \"$value\"")
    return value
}
