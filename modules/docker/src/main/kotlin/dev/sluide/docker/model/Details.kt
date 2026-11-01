package dev.sluide.docker.model

import java.time.Instant

/** One variable of a container's environment, in the order the container has them. */
data class EnvVariable(
    val name: String,
    val value: String,
)

/**
 * A mount of a container.
 *
 * @property type `volume`, `bind`, `tmpfs`, …
 * @property name the volume's name for a volume, else `null`.
 * @property source the path on the host (the volume's folder for volumes).
 * @property destination the path in the container.
 */
data class Mount(
    val type: String,
    val name: String?,
    val source: String?,
    val destination: String,
    val readWrite: Boolean,
)

/** A network a container is connected to, with its address there. */
data class NetworkAttachment(
    val name: String,
    val ipAddress: String?,
    val aliases: List<String> = emptyList(),
)

/**
 * What `container inspect` tells about a container, for its Overview and Environment.
 *
 * @property startedAt when it last started; `null` if never.
 * @property finishedAt when it last ended; `null` if never.
 * @property exitCode the exit code of its last run; `null` unless it ended.
 * @property health `healthy`, `unhealthy` or `starting` if it has a health check, else `null`.
 * @property command the command (`Config.Cmd`), [entrypoint] the entrypoint (`Config.Entrypoint`).
 */
data class ContainerDetails(
    val id: String,
    val name: String,
    val image: String,
    val imageId: String?,
    val state: ContainerState,
    val createdAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val exitCode: Int?,
    val restartCount: Int,
    val health: String?,
    val command: List<String>,
    val entrypoint: List<String>,
    val env: List<EnvVariable>,
    val labels: Map<String, String>,
    val mounts: List<Mount>,
    val networks: List<NetworkAttachment>,
    val ports: List<PortMapping>,
)
