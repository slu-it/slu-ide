package dev.sluide.docker.model

import java.time.Instant

/** The label Compose puts on its containers: the project name. */
const val COMPOSE_PROJECT_LABEL = "com.docker.compose.project"

/** The label Compose puts on its containers: the service name. */
const val COMPOSE_SERVICE_LABEL = "com.docker.compose.service"

/** The label Compose puts on its containers: the folder the project was started from. */
const val COMPOSE_WORKING_DIR_LABEL = "com.docker.compose.project.working_dir"

/** The state of a container, normalised over Docker's and Podman's words for it. */
enum class ContainerState {
    CREATED,
    RUNNING,
    PAUSED,
    RESTARTING,
    EXITED,
    DEAD,
    REMOVING,
    UNKNOWN,
    ;

    companion object {
        /** The state for the CLI's word: Docker's (`running`, `exited`, …) or Podman's (`stopped`, `configured`). */
        fun of(text: String?): ContainerState =
            when (text?.trim()?.lowercase()) {
                "created", "configured", "initialized" -> CREATED
                "running", "up" -> RUNNING
                "paused" -> PAUSED
                "restarting" -> RESTARTING
                "exited", "stopped" -> EXITED
                "dead" -> DEAD
                "removing" -> REMOVING
                else -> UNKNOWN
            }
    }
}

/**
 * A port of a container: published on the host ([hostPort] set) or only exposed ([hostPort] `null`). Docker lists a
 * port published on all addresses once per address family (`0.0.0.0` and `::`).
 *
 * @property hostIp the host address it is published on, e.g. `0.0.0.0`, `127.0.0.1`, `::`; `null` if unknown or
 *   not published.
 * @property protocol `tcp`, `udp` or `sctp`.
 */
data class PortMapping(
    val hostIp: String?,
    val hostPort: Int?,
    val containerPort: Int,
    val protocol: String = "tcp",
)

/**
 * A container as `ps -a` lists it.
 *
 * @property id the full id.
 * @property name the first name, without a leading `/`.
 * @property image the image as the container was created from it, e.g. `nginx:1.27`; an id if its tag is gone.
 * @property imageId the image's id; `null` with Docker, whose `ps` does not tell it.
 * @property status the CLI's text, e.g. "Up 3 minutes (healthy)", "Exited (0) 2 hours ago".
 * @property volumes the names of the volumes it mounts; empty with Podman, whose `ps` lists only mount targets.
 * @property networks the names of the networks it is connected to.
 */
data class Container(
    val id: String,
    val name: String,
    val image: String,
    val imageId: String?,
    val state: ContainerState,
    val status: String,
    val ports: List<PortMapping>,
    val labels: Map<String, String>,
    val createdAt: Instant?,
    val volumes: List<String>,
    val networks: List<String>,
) {
    /** The Compose project it belongs to, from its label; `null` for a standalone container. */
    val composeProject: String? get() = labels[COMPOSE_PROJECT_LABEL]?.takeIf { it.isNotBlank() }

    /** Its Compose service, from its label. */
    val composeService: String? get() = labels[COMPOSE_SERVICE_LABEL]?.takeIf { it.isNotBlank() }

    /** The folder its Compose project was started from, from its label. */
    val composeWorkingDir: String? get() = labels[COMPOSE_WORKING_DIR_LABEL]?.takeIf { it.isNotBlank() }
}

/**
 * One tag of an image (`repository:tag`); an image with several tags is listed once per tag, an untagged one once with
 * [repository] and [tag] `null`.
 *
 * @property id the full id, e.g. `sha256:…`.
 * @property digest the content digest, if known.
 * @property size the size in bytes as the CLI reports it (Docker rounds it, e.g. 25.4MB).
 * @property dangling whether the image has no tag at all.
 */
data class Image(
    val id: String,
    val repository: String?,
    val tag: String?,
    val digest: String?,
    val size: Long,
    val createdAt: Instant?,
    val dangling: Boolean,
) {
    /** What commands should name it by: `repository:tag`, else the id. */
    val reference: String get() = if (repository != null && tag != null) "$repository:$tag" else id
}

/**
 * A volume.
 *
 * @property anonymous whether the engine named it itself (a container's `VOLUME` without a name).
 * @property inUse whether a container mounts it, as the engine tells (`volume ls --filter dangling=true`); `null` if
 *   unknown, then [pruneCandidates] looks at the containers' [Container.volumes].
 */
data class Volume(
    val name: String,
    val driver: String,
    val mountpoint: String?,
    val labels: Map<String, String>,
    val anonymous: Boolean,
    val inUse: Boolean? = null,
)

/** The networks every engine creates itself, which cannot be removed. */
val BUILTIN_NETWORKS = setOf("bridge", "host", "none", "podman")

/**
 * A network.
 *
 * @property scope `local`, `swarm` or `global`.
 * @property builtin whether it is one of the engine's own networks ([BUILTIN_NETWORKS]).
 */
data class Network(
    val id: String,
    val name: String,
    val driver: String,
    val scope: String,
    val labels: Map<String, String> = emptyMap(),
    val createdAt: Instant? = null,
) {
    val builtin: Boolean get() = name in BUILTIN_NETWORKS
}
