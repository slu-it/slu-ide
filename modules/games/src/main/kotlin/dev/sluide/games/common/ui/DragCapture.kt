package dev.sluide.games.common.ui

import javafx.event.Event
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent

/**
 * Keeps a primary-button drag that started on [board] with the board until the release.
 *
 * The symptom, seen on macOS and not reproduced on Linux: a SplitPane divider moves while the player aims or drags a
 * card across it. The presumed cause is that JavaFX loses the press target during the drag and delivers MOUSE_DRAGGED
 * and MOUSE_RELEASED to the node under the cursor; this is not established. While a drag lasts, a drag or release
 * aimed at a node outside the board is consumed before that node sees it and re-fired at the board (coordinates
 * relative to the board); so is a secondary or middle press while the primary button is held. Any other press outside
 * the board, a primary release and the board leaving its scene end the capture. Window filters and scene filters added
 * before this one see a redirected event twice: the consumed original, then the copy. FX thread.
 */
internal fun keepDragsOn(board: Node) {
    DragCapture(board)
}

private class DragCapture(
    private val board: Node,
) {
    private var scene: Scene? = null

    private val pressFilter =
        EventHandler<MouseEvent> { event ->
            if (insideBoard(event.target)) return@EventHandler
            // another button during the drag belongs to it (Mini Golf's right click cancels the aim)
            if (event.button != MouseButton.PRIMARY && event.isPrimaryButtonDown) moveToBoard(event) else stop()
        }
    private val redirect =
        EventHandler<MouseEvent> { event ->
            if (!insideBoard(event.target)) moveToBoard(event)
            if (event.eventType == MouseEvent.MOUSE_RELEASED && event.button == MouseButton.PRIMARY) stop()
        }

    init {
        board.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY) start()
        }
        board.sceneProperty().addListener { _ -> stop() }
    }

    private fun moveToBoard(event: MouseEvent) {
        val copy = event.copyFor(board, board)
        event.consume()
        Event.fireEvent(board, copy)
    }

    private fun insideBoard(target: Any?): Boolean {
        var node = target as? Node
        while (node != null) {
            if (node === board) return true
            node = node.parent
        }
        return false
    }

    private fun start() {
        if (scene != null) return
        val current = board.scene ?: return
        scene = current
        current.addEventFilter(MouseEvent.MOUSE_DRAGGED, redirect)
        current.addEventFilter(MouseEvent.MOUSE_RELEASED, redirect)
        current.addEventFilter(MouseEvent.MOUSE_PRESSED, pressFilter)
    }

    private fun stop() {
        val current = scene ?: return
        scene = null
        current.removeEventFilter(MouseEvent.MOUSE_DRAGGED, redirect)
        current.removeEventFilter(MouseEvent.MOUSE_RELEASED, redirect)
        current.removeEventFilter(MouseEvent.MOUSE_PRESSED, pressFilter)
    }
}
