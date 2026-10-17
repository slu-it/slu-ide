package dev.sluide.fileviewers

import javafx.application.Platform
import javafx.scene.Node
import javafx.util.Subscription

/**
 * Focuses a node of a view at once if it is in a scene, or else as soon as it gets one: a view is often asked for the
 * focus before it is shown, and a split pane adds its items to the scene only when it is skinned. A newer [request]
 * or [cancel] drops a pending one. FX thread only.
 */
internal class FocusWhenShown {
    /** Counts the requests; a pending one runs only while it is the last. */
    private var requests = 0
    private var waiting: Subscription = Subscription.EMPTY

    /**
     * Calls [focus] (by default, focuses [target]) at once if [target] is in a scene, otherwise on the next pulse after
     * it got one, if it is still in a scene then. [focus] decides itself whether the view may still take the focus.
     */
    fun request(
        target: Node,
        focus: () -> Unit = target::requestFocus,
    ) {
        cancel()
        if (target.scene != null) {
            focus()
            return
        }
        val request = requests
        waiting =
            target.sceneProperty().subscribe { scene ->
                if (scene == null || request != requests) return@subscribe
                waiting.unsubscribe()
                waiting = Subscription.EMPTY
                // The node is added in the middle of a layout or skinning; focus it once that is done.
                Platform.runLater { if (request == requests && target.scene != null) focus() }
            }
    }

    /** Drops the pending request, if any. */
    fun cancel() {
        requests++
        waiting.unsubscribe()
        waiting = Subscription.EMPTY
    }

    companion object {
        private val KEY = Any()

        /** The requests for the view whose node is [node], kept with it; for views without their own. */
        fun of(node: Node): FocusWhenShown = node.properties.getOrPut(KEY) { FocusWhenShown() } as FocusWhenShown
    }
}
