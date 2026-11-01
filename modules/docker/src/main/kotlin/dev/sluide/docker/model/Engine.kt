package dev.sluide.docker.model

/** Which CLI Thoreau talks to; their JSON and some options differ, the model does not. */
enum class EngineFlavor {
    DOCKER,
    PODMAN,
}

/**
 * The engine the CLI reaches, from `version` (and `context show` for Docker).
 *
 * @property flavor the CLI: [EngineFlavor.PODMAN] for `podman` (also behind a `docker` alias script), else Docker.
 * @property serverVersion the version of the engine (for Podman without a server part, the client's).
 * @property clientVersion the version of the CLI.
 * @property platform the engine's product name if it tells one, e.g. "Docker Engine - Community",
 *   "Docker Desktop 4.40.0".
 * @property context the current Docker context (`docker context show`); `null` for Podman or when it cannot be read.
 */
data class EngineInfo(
    val flavor: EngineFlavor,
    val serverVersion: String,
    val clientVersion: String? = null,
    val platform: String? = null,
    val context: String? = null,
)

/** The four kinds of things the Docker tab lists; also what an event makes reload. */
enum class ResourceKind(
    /** The CLI's object command, e.g. `docker volume inspect`. */
    val command: String,
) {
    CONTAINER("container"),
    IMAGE("image"),
    VOLUME("volume"),
    NETWORK("network"),
}

/** Lifecycle actions on a container, each one CLI command with the container's id. */
enum class ContainerAction(
    /** The CLI command, e.g. `docker stop <id>`. */
    val command: String,
) {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
    PAUSE("pause"),
    UNPAUSE("unpause"),
    KILL("kill"),
}
