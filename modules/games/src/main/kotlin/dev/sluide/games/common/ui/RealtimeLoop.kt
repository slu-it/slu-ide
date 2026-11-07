package dev.sluide.games.common.ui

import dev.sluide.games.common.RealtimeSession
import javafx.animation.AnimationTimer
import javafx.beans.value.ChangeListener
import javafx.collections.ListChangeListener
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ContextMenu
import javafx.stage.PopupWindow
import javafx.stage.Window

/**
 * Connects a real-time game board to its session: the board's focus goes to [RealtimeSession.focusChanged], the board
 * leaving its scene or a context menu of its window showing counts as losing the focus, and an [AnimationTimer] runs
 * only while [RealtimeSession.needsAnimation], calling [RealtimeSession.tick] and then [onFrame] each pulse. FX thread.
 */
internal class RealtimeLoop(
    private val board: Node,
    private val session: RealtimeSession,
    private val onFrame: () -> Unit,
) {
    private var running = false

    /** True while the animation timer runs. */
    internal val isRunning: Boolean get() = running

    private val timer =
        object : AnimationTimer() {
            override fun handle(now: Long) {
                session.tick(now)
                onFrame()
                if (!session.needsAnimation) stopTimer()
            }
        }

    private val focusListener = ChangeListener<Boolean> { _, _, _ -> sync() }

    // A node that leaves its scene keeps its focus flag until the next pulse, so leaving counts as losing it; a
    // node that (re)joins one reports its real state, which heals a remove and re-add within one pulse.
    private val sceneListener = ChangeListener<Scene?> { _, _, _ -> sync() }

    // A showing context menu (the menu bar's menus too) never takes the board's focus, so the game would run on.
    private val windowsListener = ListChangeListener<Window> { _ -> sync() }

    init {
        board.focusedProperty().addListener(focusListener)
        board.sceneProperty().addListener(sceneListener)
        Window.getWindows().addListener(windowsListener)
        if (board.isFocused) sync()
    }

    /** The session's focus: the board is in a scene and focused and no context menu of its window is showing. */
    private fun sync() = session.focusChanged(board.scene != null && board.isFocused && !menuShowing())

    private fun menuShowing(): Boolean {
        val boardWindow = board.scene?.window ?: return false
        return Window.getWindows().any { it is ContextMenu && it.isShowing && ownedBy(it, boardWindow) }
    }

    private fun ownedBy(
        popup: Window,
        owner: Window,
    ): Boolean {
        var window: Window? = popup
        while (window != null) {
            if (window === owner) return true
            window = (window as? PopupWindow)?.ownerWindow
        }
        return false
    }

    /** Starts or stops the timer by [RealtimeSession.needsAnimation]; the view calls it from its change callback. */
    fun update() {
        if (session.needsAnimation) {
            if (!running) {
                running = true
                timer.start()
            }
        } else {
            stopTimer()
        }
    }

    fun dispose() {
        stopTimer()
        board.focusedProperty().removeListener(focusListener)
        board.sceneProperty().removeListener(sceneListener)
        Window.getWindows().removeListener(windowsListener)
    }

    private fun stopTimer() {
        if (!running) return
        running = false
        timer.stop()
    }
}
