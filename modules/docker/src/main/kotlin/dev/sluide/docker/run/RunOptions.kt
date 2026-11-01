package dev.sluide.docker.run

/** An environment variable of a run; the value is plain text. */
internal data class RunEnvVar(
    val name: String,
    val value: String,
)

/**
 * A volume of a run: [source] is an absolute host path, a path starting with `./` (project folder) or `~/` (home), or a
 * named volume; [target] is the absolute path in the container.
 */
internal data class RunVolume(
    val source: String,
    val target: String,
    val readOnly: Boolean = false,
)

/**
 * What the Run Container dialog edits: the values of one `docker run`.
 *
 * @property image the reference to run (`name:tag`, or an image id).
 * @property name the container's name; empty for the engine's choice.
 * @property ports `[host-ip:]host-port:container-port[/tcp|udp]` or just a container port, one per entry.
 * @property network the network to join; `null` for the engine's default.
 * @property command the arguments after the image, as typed (shell words).
 * @property detached whether the container runs in the background (`-d`).
 * @property removeWhenStopped whether the engine removes the container when it stops (`--rm`).
 */
internal data class RunOptions(
    val image: String,
    val name: String = "",
    val ports: List<String> = emptyList(),
    val environment: List<RunEnvVar> = emptyList(),
    val volumes: List<RunVolume> = emptyList(),
    val network: String? = null,
    val command: String = "",
    val detached: Boolean = true,
    val removeWhenStopped: Boolean = false,
)
