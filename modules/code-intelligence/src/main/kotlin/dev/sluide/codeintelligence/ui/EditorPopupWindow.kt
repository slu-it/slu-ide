package dev.sluide.codeintelligence.ui

import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.application.Platform
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.stage.Popup
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Subscription

/** The stylesheet of the code intelligence popups; popups do not inherit the editor's stylesheets. */
internal val POPUP_CSS: String =
    checkNotNull(EditorPopupWindow::class.java.getResource("code-intelligence-popups.css")).toExternalForm()

/**
 * A popup window over an editor's text, the common part of [CaretPopup] and [HoverPopup]: placed at an anchor range
 * ([popupPosition]), and hidden when the editor loses the focus or leaves the scene, when its window moves, is
 * minimized or loses the focus, when the editor scrolls (a wheel turn, or another first line shown)
 * and on a click outside; it follows its anchor when a shown line is edited. The focus stays in the editor. FX thread.
 *
 * The editor API gives no node, so the popup's owner is the text area learned from the events the editor's filters
 * see ([ExtensibleCodeEditor.addEventFilter] filters on the text area): the press of the key or the mouse that opened
 * it, or any event before. Without one yet, the focused window owns it.
 */
internal class EditorPopupWindow(
    private val editor: ExtensibleCodeEditor,
    private val hidden: () -> Unit,
) : AutoCloseable {
    /** The editor's text area, once an event showed it. */
    private var area: Node? = null

    private val tracking =
        listOf(
            editor.addEventFilter(MouseEvent.ANY) { area = it.source as? Node ?: area },
            editor.addEventFilter(KeyEvent.ANY) { area = it.source as? Node ?: area },
        )

    private var popupWindow: Popup? = null
    private var watches: List<Subscription> = emptyList()
    private var anchorBounds: Bounds? = null
    private var anchorStart = 0
    private var anchorEnd = 0

    /** The first line shown when the popup opened: another one means the editor scrolled. */
    private var firstShownLine: Int? = null

    /** Whether the popup shows. */
    val isShowing: Boolean get() = popupWindow?.isShowing == true

    /** The popup's screen bounds while it shows. */
    val screenBounds: Bounds?
        get() = popupWindow?.takeIf { it.isShowing }?.let { BoundingBox(it.x, it.y, it.width, it.height) }

    /** The anchor's screen bounds when the popup was last placed. */
    val anchor: Bounds? get() = anchorBounds.takeIf { isShowing }

    /**
     * Shows [content] for the text [start, end) (a caret at [start] when equal), or places it again there if it shows;
     * `false` if the range is not on screen or there is no window to own the popup.
     */
    fun show(
        content: Parent,
        start: Int,
        end: Int = start,
    ): Boolean {
        val bounds = editor.screenBounds(start, end) ?: return false
        val popup = popupWindow ?: create()
        if (popup.content.singleOrNull() !== content) {
            if (POPUP_CSS !in content.stylesheets) content.stylesheets += POPUP_CSS
            popup.content.setAll(content)
        }
        anchorStart = start
        anchorEnd = end
        if (!popup.isShowing) {
            val owner = ownerWindow() ?: return false
            popup.show(owner, bounds.minX, bounds.maxY)
            firstShownLine = editor.shownLines.firstOrNull()
            watches = watch(owner)
        }
        place(bounds)
        return true
    }

    /** Places the showing popup again at its anchor, after its content changed size. */
    fun relayout() {
        val popup = popupWindow ?: return
        if (!popup.isShowing) return
        popup.sizeToScene()
        editor.screenBounds(anchorStart, anchorEnd)?.let(::place)
    }

    /** Hides the popup; [hidden] is told. */
    fun hide() {
        popupWindow?.hide()
    }

    /** Hides the popup and stops following the editor's events. */
    override fun close() {
        hide()
        tracking.forEach { it.unsubscribe() }
    }

    private fun create(): Popup =
        Popup()
            .apply {
                isAutoHide = true
                // A click into the editor hides the popup and still moves the caret there.
                consumeAutoHidingEvents = false
                isAutoFix = false
                setOnHidden {
                    watches.forEach { it.unsubscribe() }
                    watches = emptyList()
                    hidden()
                }
            }.also { popupWindow = it }

    private fun place(bounds: Bounds) {
        val popup = popupWindow ?: return
        anchorBounds = bounds
        val screen =
            Screen.getScreensForRectangle(bounds.minX, bounds.minY, bounds.width, bounds.height).firstOrNull()
                ?: Screen.getPrimary()
        val position = popupPosition(bounds, popup.width, popup.height, screen.visualBounds)
        popup.x = position.x
        popup.y = position.y
    }

    private fun ownerWindow(): Window? =
        area?.scene?.window?.takeIf { it.isShowing } ?: Window.getWindows().firstOrNull { it.isFocused && it !is Popup }

    private fun watch(owner: Window): List<Subscription> {
        // Change subscriptions only: a window that is not focused when the popup opens (no window manager) keeps it.
        val watches =
            mutableListOf(
                owner.xProperty().subscribe { _, _ -> hide() },
                owner.yProperty().subscribe { _, _ -> hide() },
                owner.focusedProperty().subscribe { _, focused -> if (!focused) hide() },
                editor.addEventFilter(ScrollEvent.SCROLL) { hide() },
                editor.onShownLinesChanged(::shownLinesChanged),
            )
        (owner as? Stage)?.let { stage ->
            watches += stage.iconifiedProperty().subscribe { _, iconified -> if (iconified) hide() }
        }
        area?.let { node ->
            watches += node.focusedProperty().subscribe { _, focused -> if (!focused) hide() }
            watches += node.sceneProperty().subscribe { _, scene -> if (scene == null) hide() }
        }
        return watches
    }

    /**
     * The shown lines changed: scrolled (another first line) hides the popup; an edit of a shown line (typing while
     * a completion popup filters) or a resize places it again at its anchor, once the text is laid out again, and
     * hides it if the anchor is no longer on screen then.
     */
    private fun shownLinesChanged() {
        if (editor.shownLines.firstOrNull() != firstShownLine) {
            hide()
            return
        }
        Platform.runLater {
            if (!isShowing) return@runLater
            val bounds = editor.screenBounds(anchorStart, anchorEnd)
            if (bounds == null) hide() else place(bounds)
        }
    }
}
