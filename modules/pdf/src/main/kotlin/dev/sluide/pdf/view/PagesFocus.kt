package dev.sluide.pdf.view

import dev.sluide.fileviewers.FocusWhenShown
import javafx.beans.value.ChangeListener
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.InputEvent
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent

/**
 * The focus of a PDF view's [pages] (FileView `focus` and `focusText`), which other nodes of the view's [root] hide or
 * keep out of the scene for a while: the split pane puts the pages into the scene a pulse after the root, and a notice
 * ([noticeShown]) hides them while the document loads. A request waits for the pages to be in the scene, and, while the
 * notice shows, for [shown]. A waiting request is dropped when the user clicks or types outside the view, or when the
 * focus moves to another node of the view (the search field, a panel, the page field), since the focus then belongs
 * where the user put it; the scene's own focus moves outside the view (a hidden tab's editor losing it) do not count.
 * FX thread only.
 */
internal class PagesFocus(
    private val root: Node,
    private val pages: Node,
    private val noticeShown: () -> Boolean,
) {
    private val pending = FocusWhenShown()
    private var onShow = false
    private var watchedScene: Scene? = null
    private val focusInside =
        ChangeListener<Node?> { _, _, owner ->
            if (owner != null && owner !== pages && generateSequence(owner) { it.parent }.any { it === root }) drop()
        }
    private val outsideInput =
        EventHandler<InputEvent> { event ->
            val target = event.target as? Node
            if (target == null || generateSequence(target) { it.parent }.none { it === root }) drop()
        }

    /** Focuses the pages at once, or, while the notice hides them, once they show. */
    fun focusText() {
        if (noticeShown()) {
            onShow = true
            watchInput()
        } else {
            focusPages()
        }
    }

    /** Focuses the pages as [focusText] does, once they are in the scene. */
    fun focus() {
        watchInput()
        pending.request(pages, ::focusText)
    }

    /** The notice made way for the pages: a request that waited for them goes on. */
    fun shown() {
        if (!onShow) return
        onShow = false
        pending.request(pages, ::focusPages)
    }

    /** Drops a waiting request. */
    fun drop() {
        pending.cancel()
        onShow = false
        unwatchInput()
    }

    private fun focusPages() {
        unwatchInput()
        pages.requestFocus()
    }

    private fun watchInput() {
        val scene = root.scene ?: return
        if (scene === watchedScene) return
        unwatchInput()
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, outsideInput)
        scene.addEventFilter(KeyEvent.KEY_PRESSED, outsideInput)
        scene.focusOwnerProperty().addListener(focusInside)
        watchedScene = scene
    }

    private fun unwatchInput() {
        watchedScene?.removeEventFilter(MouseEvent.MOUSE_PRESSED, outsideInput)
        watchedScene?.removeEventFilter(KeyEvent.KEY_PRESSED, outsideInput)
        watchedScene?.focusOwnerProperty()?.removeListener(focusInside)
        watchedScene = null
    }
}
