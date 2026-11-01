package dev.sluide.docker.events

import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.ResourceKind

/** Container actions that change nothing a list shows: exec sessions, attaching, resizing, copying files. */
private val quietContainerActions =
    setOf(
        "exec_create",
        "exec_start",
        "exec_die",
        "exec_detach",
        "attach",
        "detach",
        "resize",
        "top",
        "export",
        "copy",
        "archive-path",
        "extract-to-dir",
        "init",
        "cleanup",
        "sync",
        "checkpoint",
    )

/**
 * The lists an [event] makes stale:
 * - container events reload the containers; creating or removing one also the volumes and networks, whose use it
 *   changes; a commit also the images. Exec sessions (health checks run one every few seconds) and similar change
 *   nothing and reload nothing.
 * - image, volume and network events reload their list; a network connect or disconnect also the containers, which
 *   list their networks. Volume mounts reload nothing (the container's start or stop does).
 * - other types (`builder`, `plugin`, `pod`, `daemon`, …) reload nothing.
 */
internal fun reloadsFor(event: DockerEvent): Set<ResourceKind> {
    // "health_status: healthy", "exec_create: sh -c …": the part before the colon is the action.
    val action =
        event.action
            .substringBefore(':')
            .trim()
            .lowercase()
    return when (event.type.lowercase()) {
        "container" -> {
            when (action) {
                in quietContainerActions -> {
                    emptySet()
                }

                "create", "destroy", "remove" -> {
                    setOf(ResourceKind.CONTAINER, ResourceKind.VOLUME, ResourceKind.NETWORK)
                }

                "commit" -> {
                    setOf(ResourceKind.CONTAINER, ResourceKind.IMAGE)
                }

                else -> {
                    setOf(ResourceKind.CONTAINER)
                }
            }
        }

        "image" -> {
            setOf(ResourceKind.IMAGE)
        }

        "volume" -> {
            if (action == "mount" || action == "unmount") emptySet() else setOf(ResourceKind.VOLUME)
        }

        "network" -> {
            if (action == "connect" || action == "disconnect") {
                setOf(ResourceKind.CONTAINER)
            } else {
                setOf(ResourceKind.NETWORK)
            }
        }

        else -> {
            emptySet()
        }
    }
}
