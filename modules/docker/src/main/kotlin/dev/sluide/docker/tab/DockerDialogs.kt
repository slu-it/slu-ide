package dev.sluide.docker.tab

import dev.sluide.docker.model.ContainerState

/** What Create Network asks for. */
internal data class NewNetwork(
    val name: String,
    val driver: String,
)

/** The drivers Create Network offers, the default first. */
internal val NETWORK_DRIVERS = listOf("bridge", "overlay", "macvlan")

/**
 * The questions of the Docker tab's actions, without controls, so that the flows can be tested with a fake
 * (ADR 0084). Each call waits for the answer; `null` (or `false`) means cancelled.
 */
internal interface DockerDialogs {
    /** Asks [message] with a [confirm] button and Cancel. */
    fun confirm(
        title: String,
        message: String,
        confirm: String,
    ): Boolean

    /** Asks [message] above the [items] that the action concerns (what a prune removes). */
    fun confirmList(
        title: String,
        message: String,
        items: List<String>,
        confirm: String,
    ): Boolean

    /**
     * Asks before removing a container in [state] ([removeContainerMessage]); the answer is whether to remove its
     * anonymous volumes too.
     */
    fun removeContainer(
        name: String,
        state: ContainerState,
    ): Boolean?

    /**
     * Asks before removing an image that [stoppedUsers] (container names) were created from, if any; the answer is
     * whether to force the removal.
     */
    fun removeImage(
        reference: String,
        stoppedUsers: List<String>,
    ): Boolean?

    /** Asks for the `name:tag` to pull. */
    fun askPull(): String?

    /** Asks for the name of a new volume. */
    fun askVolumeName(): String?

    /** Asks for the name and driver of a new network. */
    fun askNetwork(): NewNetwork?
}
