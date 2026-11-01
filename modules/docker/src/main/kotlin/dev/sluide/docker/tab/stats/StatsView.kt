package dev.sluide.docker.tab.stats

import dev.sluide.docker.DockerSession
import javafx.scene.Node
import javafx.scene.layout.StackPane

/**
 * The Stats tab of a container's details in the Docker tab: live CPU, memory, network and block IO of one container
 * through [session]. The Docker tab calls [show] with the selected container while the Stats tab is shown and with
 * `null` otherwise, so that stats stream only while they are visible. FX thread only.
 */
internal class StatsView(
    private val session: DockerSession,
) {
    /** The view's node. */
    val node: Node = StackPane()

    /**
     * Shows the stats of the container with [containerId]; `null` stops the stream and clears the view. Calling it
     * again with the container already shown does nothing.
     */
    fun show(containerId: String?) {
    }

    /** Stops the stream for good; called when the Docker tab closes. */
    fun dispose() {
    }
}
