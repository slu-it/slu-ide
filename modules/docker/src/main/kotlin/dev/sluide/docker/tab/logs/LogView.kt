package dev.sluide.docker.tab.logs

import dev.sluide.docker.DockerSession
import javafx.scene.Node
import javafx.scene.layout.StackPane

/**
 * The Logs tab of a container's details in the Docker tab: follows the logs of one container through [session]. The
 * Docker tab calls [show] with the selected container while the Logs tab is shown and with `null` otherwise, so that
 * logs stream only while they are visible. FX thread only.
 */
internal class LogView(
    private val session: DockerSession,
) {
    /** The view's node. */
    val node: Node = StackPane()

    /**
     * Shows and follows the logs of the container with [containerId]; `null` stops following and clears the view.
     * Calling it again with the container already shown does nothing.
     */
    fun show(containerId: String?) {
    }

    /** Stops following for good; called when the Docker tab closes. */
    fun dispose() {
    }
}
