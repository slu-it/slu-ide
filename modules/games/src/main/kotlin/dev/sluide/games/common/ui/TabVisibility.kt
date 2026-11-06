package dev.sluide.games.common.ui

import javafx.beans.InvalidationListener
import javafx.beans.value.ChangeListener
import javafx.scene.Node
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.Window

/**
 * Calls [onHidden] with true when [node] cannot be seen (no scene, no window, window not showing or iconified) and
 * with false when it can; once at construction. FX thread.
 */
internal class TabVisibility(
    private val node: Node,
    private val onHidden: (Boolean) -> Unit,
) {
    private val hiddenListener = InvalidationListener { update() }
    private val sceneListener = ChangeListener<Scene?> { _, _, scene -> observe(scene) }
    private var observedScene: Scene? = null
    private var observedWindow: Window? = null
    private var disposed = false

    init {
        node.sceneProperty().addListener(sceneListener)
        observe(node.scene)
    }

    /** Follows the scene and its window. */
    private fun observe(scene: Scene?) {
        observedScene?.windowProperty()?.removeListener(hiddenListener)
        observedScene = scene
        scene?.windowProperty()?.addListener(hiddenListener)
        update()
    }

    private fun update() {
        if (disposed) return
        val window = node.scene?.window
        if (window !== observedWindow) {
            unobserveWindow()
            observedWindow = window
            window?.showingProperty()?.addListener(hiddenListener)
            (window as? Stage)?.iconifiedProperty()?.addListener(hiddenListener)
        }
        onHidden(window == null || !window.isShowing || (window is Stage && window.isIconified))
    }

    private fun unobserveWindow() {
        observedWindow?.showingProperty()?.removeListener(hiddenListener)
        (observedWindow as? Stage)?.iconifiedProperty()?.removeListener(hiddenListener)
        observedWindow = null
    }

    fun dispose() {
        disposed = true
        node.sceneProperty().removeListener(sceneListener)
        observedScene?.windowProperty()?.removeListener(hiddenListener)
        observedScene = null
        unobserveWindow()
    }
}
