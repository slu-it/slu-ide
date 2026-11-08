package dev.sluide.codeintelligence.ui

import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.application.Platform
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.Parent
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
 * A popup window over an editor's text, the common part of [CaretPopup] and [HoverPopup], owned by the editor's text
 * area ([ExtensibleCodeEditor.popupOwner]): placed at an anchor range ([popupPosition]), and hidden when the area loses
 * the focus or leaves the scene, when its window moves, is minimized or loses the focus, when the editor scrolls (a
 * wheel turn, or another first line shown, [shownLinesReaction]) and on a click outside; it follows its anchor when a
 * shown line is edited. Escape is left to the popups' own key bindings, so that it closes the popup and nothing else
 * (an open find bar stays). The focus stays in the editor: nothing in the popup takes it ([noFocusTraversal] on the
 * content each time it is shown or laid out), so Enter and Space never reach a button or link inside. FX thread.
 */
internal class EditorPopupWindow(
    private val editor: ExtensibleCodeEditor,
    private val hidden: () -> Unit,
) : AutoCloseable {
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
     * `false` if the range is not on screen or the editor is not in a shown window.
     */
    fun show(
        content: Parent,
        start: Int,
        end: Int = start,
    ): Boolean {
        val bounds = editor.screenBounds(start, end) ?: return false
        val area = editor.popupOwner ?: return false
        val owner = area.scene?.window?.takeIf { it.isShowing } ?: return false
        val popup = popupWindow ?: create()
        if (popup.content.singleOrNull() !== content) {
            if (POPUP_CSS !in content.stylesheets) content.stylesheets += POPUP_CSS
            popup.content.setAll(content)
        }
        noFocusTraversal(content)
        anchorStart = start
        anchorEnd = end
        if (!popup.isShowing) {
            popup.show(owner, bounds.minX, bounds.maxY)
            firstShownLine = editor.shownLines.firstOrNull()
            watches = watch(owner, area)
        }
        place(bounds)
        return true
    }

    /** Places the showing popup again at its anchor, after its content changed size. */
    fun relayout() {
        val popup = popupWindow ?: return
        if (!popup.isShowing) return
        popup.content.forEach(::noFocusTraversal)
        popup.sizeToScene()
        editor.screenBounds(anchorStart, anchorEnd)?.let(::place)
    }

    /** Hides the popup; [hidden] is told. */
    fun hide() {
        popupWindow?.hide()
    }

    /** Hides the popup for good. */
    override fun close() = hide()

    private fun create(): Popup =
        Popup()
            .apply {
                isAutoHide = true
                // A click into the editor hides the popup and still moves the caret there.
                consumeAutoHidingEvents = false
                // Hidden on Escape by JavaFX, the key would go on to the editor and close its find bar too.
                isHideOnEscape = false
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

    private fun watch(
        owner: Window,
        area: Node,
    ): List<Subscription> {
        // Change subscriptions only: a window that is not focused when the popup opens (no window manager) keeps it.
        val watches =
            mutableListOf(
                owner.xProperty().subscribe { _, _ -> hide() },
                owner.yProperty().subscribe { _, _ -> hide() },
                owner.focusedProperty().subscribe { _, focused -> if (!focused) hide() },
                area.focusedProperty().subscribe { _, focused -> if (!focused) hide() },
                area.sceneProperty().subscribe { _, scene -> if (scene == null) hide() },
                editor.addEventFilter(ScrollEvent.SCROLL) { hide() },
                editor.onShownLinesChanged(::shownLinesChanged),
            )
        (owner as? Stage)?.let { stage ->
            watches += stage.iconifiedProperty().subscribe { _, iconified -> if (iconified) hide() }
        }
        return watches
    }

    /** The shown lines changed: hidden if the editor scrolled, else placed again at the anchor once laid out. */
    private fun shownLinesChanged() {
        when (shownLinesReaction(firstShownLine, editor.shownLines.firstOrNull())) {
            ShownLinesReaction.HIDE -> {
                hide()
            }

            ShownLinesReaction.PLACE_AGAIN -> {
                Platform.runLater {
                    if (!isShowing) return@runLater
                    val bounds = editor.screenBounds(anchorStart, anchorEnd)
                    if (bounds == null) hide() else place(bounds)
                }
            }
        }
    }
}

/**
 * Makes [node] and everything in it not focus traversable, so that nothing in a popup takes the focus from the editor
 * (a click on a button or link, the popup scene's first focus).
 */
internal fun noFocusTraversal(node: Node) {
    node.isFocusTraversable = false
    if (node is Parent) node.childrenUnmodifiable.forEach(::noFocusTraversal)
}
