package dev.sluide.games.common.ui

import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.input.MouseEvent

/**
 * A mouse press anywhere in [root] that no child consumed gives [board] the keyboard focus, and so does a scroll
 * pane in [root] gaining the focus. A scroll pane around the board focuses itself on every press (its behavior
 * ignores isFocusTraversable) and consumes the press (the input map's autoConsume), so the press never bubbles to
 * [root]: the scroll panes hand the focus on instead. Without this, a press on a toolbar or status bar background
 * would leave the focus where it was. Call it on the FX thread, after the scroll panes are part of [root].
 */
internal fun focusBoardOnPress(
    root: Node,
    board: Node,
) {
    root.addEventHandler(MouseEvent.MOUSE_PRESSED) { if (!board.isFocused) board.requestFocus() }
    root
        .lookupAll(".scroll-pane")
        .filterIsInstance<ScrollPane>()
        .forEach { pane -> pane.focusedProperty().addListener { _, _, focused -> if (focused) board.requestFocus() } }
}
