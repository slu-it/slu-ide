package dev.sluide.docker.cli

import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.EngineInfo
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.model.StatsSample
import dev.sluide.docker.model.Volume
import java.nio.file.Path

/**
 * A failure of the docker (or podman) CLI: its standard error, trimmed, or a clear text if it printed none.
 *
 * @property exitCode the CLI's exit code; `null` if it did not run to an end (not startable, timed out, interrupted).
 */
class DockerException(
    message: String,
    val exitCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * What `docker compose config --format json` printed.
 *
 * @property json standard output: the resolved project as JSON.
 * @property warnings standard error of the successful run (compose's warnings, one per line; at most 64 KB, cut at
 *   a line end).
 */
data class ComposeConfigOutput(
    val json: String,
    val warnings: String,
)

/**
 * The container engine as its CLI shows it (ADR 0117): Docker or Podman, whichever executable the settings name.
 *
 * Every method blocks until the CLI is done, so none may run on the FX thread; each throws [DockerException] when the
 * CLI fails (engine not running, unknown id, refused removal). The stream methods return once the CLI runs; their
 * callbacks then run on the stream's own reader thread, one at a time and in order. Closing a stream's handle ends
 * the CLI (and what it started); no callback starts after that, `onEnd` neither (one already running may finish,
 * output still buffered is dropped). A stream that
 * ends by itself calls `onEnd` once: with `null` when the CLI exited normally (logs of a container that stopped), with
 * a [DockerException] otherwise.
 */
interface DockerCli {
    /** The engine's flavor and versions, and the current context; throws when the engine cannot be reached. */
    fun engine(): EngineInfo

    /** All containers, running or not (`ps -a`). */
    fun containers(): List<Container>

    /** All images, one entry per `repository:tag`; untagged (dangling) images once each. */
    fun images(): List<Image>

    /** All volumes, with whether a container uses them. */
    fun volumes(): List<Volume>

    /** All networks. */
    fun networks(): List<Network>

    /** What `container inspect` tells about the container with [id] (or name). */
    fun containerDetails(id: String): ContainerDetails

    /** The `inspect` JSON of the object of [kind] with [id] (or name), pretty-printed; a single object unwrapped. */
    fun inspect(
        kind: ResourceKind,
        id: String,
    ): String

    /** Runs [action] on the container with [id]: `docker start <id>` and so on. */
    fun act(
        action: ContainerAction,
        id: String,
    )

    /** Removes the container with [id]; with [volumes] also its anonymous volumes, with [force] also when running. */
    fun removeContainer(
        id: String,
        volumes: Boolean,
        force: Boolean,
    )

    /**
     * Removes an image by [reference]: a `repository:tag` removes that tag (the image when it was the last one), an id
     * the image, which fails for an image with several tags unless [force].
     */
    fun removeImage(
        reference: String,
        force: Boolean,
    )

    /** Removes the volume [name]; fails while a container uses it. */
    fun removeVolume(name: String)

    /** Removes the network with [id] (or name); fails while a container is connected. */
    fun removeNetwork(id: String)

    /** Removes dangling images; the CLI's summary, e.g. "Total reclaimed space: 25.4MB". */
    fun pruneImages(): String

    /** Removes the volumes no container uses, named ones included; the CLI's summary. */
    fun pruneVolumes(): String

    /** Removes the networks no container is connected to; the CLI's summary. */
    fun pruneNetworks(): String

    /**
     * Pulls [reference] (`name:tag`), giving each line of the CLI's progress to [onProgress] on the calling thread.
     * Interrupting that thread (cancelling the task) kills the CLI and throws [DockerException]; if [onProgress]
     * throws, the CLI is killed and the exception passed on.
     */
    fun pull(
        reference: String,
        onProgress: (String) -> Unit,
    )

    /** Creates the volume [name]. */
    fun createVolume(name: String)

    /** Creates the network [name] with [driver], or the engine's default driver if `null`. */
    fun createNetwork(
        name: String,
        driver: String?,
    )

    /**
     * Starts a detached container: `docker <args>` where [args] start with `run` and contain `-d`, as
     * [dev.sluide.docker.run.runArgs] builds them, variables inline as `-e NAME=value`; returns the new container's
     * id, trimmed. The CLI runs with its resolved environment only. Throws [DockerException] with the CLI's message
     * when it fails, when [args] do not start with `run` or when it prints no id; throws [IllegalArgumentException]
     * without `-d`, as the call has no time limit (the engine may pull the image first; interrupting the calling
     * thread kills the CLI) and an attached container would never end it.
     */
    fun run(args: List<String>): String

    /** Streams the engine's events from now on (`events`); never ends by itself while the engine runs. */
    fun events(
        onEvent: (DockerEvent) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable

    /**
     * Streams the logs of the container [id] (`logs -f --timestamps`), standard output and error mixed, starting with
     * the last [tail] lines (all if `null`); each line without its line break, with Docker's RFC 3339 timestamp first.
     */
    fun logs(
        id: String,
        tail: Int?,
        onLine: (String) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable

    /** Streams the resource use of the container [id] (`stats`), about one sample a second while it runs. */
    fun stats(
        id: String,
        onSample: (StatsSample) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable

    /**
     * Runs `docker compose <composeArgs> config --format json` in [workingDir] (the compose file's folder) and returns
     * its output. [composeArgs] are the arguments after the executable, as [dev.sluide.docker.compose.ComposeCommands]
     * `baseArgs` builds them (starting with `compose`); they are the caller's own flags, so they are not refused for
     * starting with `-`, which is why the caller must put only validated values in (files as absolute paths, checked
     * names). [environment] is laid over the resolved environment of the CLI (the Environment table's variables).
     * Throws [DockerException] with the CLI's standard error when it exits non-zero (a broken compose file).
     */
    fun composeConfig(
        workingDir: Path,
        composeArgs: List<String>,
        environment: Map<String, String>,
    ): ComposeConfigOutput
}
