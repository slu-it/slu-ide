package dev.sluide.docker.model

import java.time.Instant

/**
 * One line of `events`, normalised over Docker and Podman.
 *
 * @property type `container`, `image`, `volume`, `network`, or others the engine has (`builder`, `pod`, …).
 * @property action e.g. `start`, `die`, `destroy`, `health_status: healthy`, `exec_create: sh`; Podman's `Status`.
 * @property id the object's id (a volume's name).
 * @property name the object's name, if the event tells it.
 */
data class DockerEvent(
    val type: String,
    val action: String,
    val id: String,
    val name: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val time: Instant? = null,
)

/**
 * One sample of `stats` for a container. Sizes are bytes as the CLI rounds them; missing values are 0.
 *
 * @property cpuPercent the CPU use, 100 per fully used core.
 */
data class StatsSample(
    val cpuPercent: Double,
    val memoryUsed: Long,
    val memoryLimit: Long,
    val networkReceived: Long,
    val networkSent: Long,
    val blockRead: Long,
    val blockWritten: Long,
    val pids: Int? = null,
)
