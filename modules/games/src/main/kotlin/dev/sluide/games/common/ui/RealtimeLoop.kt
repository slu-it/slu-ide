package dev.sluide.games.common.ui

import dev.sluide.games.common.RealtimeSession
import javafx.animation.AnimationTimer
import javafx.beans.value.ChangeListener
import javafx.scene.Node
import javafx.scene.Scene

/**
 * Connects a real-time game board to its session: the board's focus goes to [RealtimeSession.focusChanged], the board
 * leaving its scene counts as losing the focus, and an [AnimationTimer] runs only while
 * [RealtimeSession.needsAnimation], calling [RealtimeSession.tick] and then [onFrame] each pulse. FX thread.
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

    private val focusListener = ChangeListener<Boolean> { _, _, focused -> session.focusChanged(focused) }
    private val sceneListener =
        // A node that leaves its scene keeps its focus flag until the next pulse, so leaving counts as losing it; a
        // node that (re)joins one reports its real state, which heals a remove and re-add within one pulse.
        ChangeListener<Scene?> { _, _, scene -> session.focusChanged(scene != null && board.isFocused) }

    init {
        board.focusedProperty().addListener(focusListener)
        board.sceneProperty().addListener(sceneListener)
        if (board.isFocused) session.focusChanged(true)
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
    }

    private fun stopTimer() {
        if (!running) return
        running = false
        timer.stop()
    }
}
