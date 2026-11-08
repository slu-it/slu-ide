package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.ui.POPUP_CSS
import dev.sluide.codeintelligence.ui.popupPosition
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.util.Subscription

/**
 * The rename field at a name (ADR 0149): a `TextField` with the current name selected in a plain [Popup] below the name,
 * owned by the editor's window. Unlike a caret popup it takes the focus: Enter renames (a name the check refuses shows
 * its problem below the field, which stays), Escape, a click outside, the field losing the focus or the window moving,
 * minimizing or losing the focus cancel; either way the editor's text area gets the focus back. FX thread.
 */
internal class RenameField(
    private val editor: ExtensibleCodeEditor,
) : AutoCloseable {
    /** The field; internal for tests. */
    internal val field =
        TextField().apply {
            styleClass += "rename-field"
            addEventFilter(KeyEvent.KEY_PRESSED, ::key)
            textProperty().subscribe { _, _ -> showProblem(null) }
        }

    /** What the check found wrong with the name typed; internal for tests. */
    internal val problem =
        Label().apply {
            styleClass += "rename-problem"
            isVisible = false
            isManaged = false
        }

    private val popup =
        Popup().apply {
            content.setAll(
                VBox(4.0, field, problem).apply {
                    styleClass += "rename-popup"
                    stylesheets += POPUP_CSS
                    // Keys the field leaves (Shift+F6, Shortcut+B) would go on to the editor beneath.
                    addEventHandler(KeyEvent.ANY) { it.consume() }
                },
            )
            isAutoHide = true
            consumeAutoHidingEvents = false
            // Escape is the field's: it cancels and goes no further (an open find bar stays).
            isHideOnEscape = false
            isAutoFix = false
            setOnHidden { hidden() }
        }

    private var onRename: (String) -> Unit = {}
    private var check: (String) -> String? = { null }
    private var watches: List<Subscription> = emptyList()

    val isShowing: Boolean get() = popup.isShowing

    /**
     * Shows the field with [name] selected below the text [start, end); Enter tells [rename] the text typed unless
     * [check] names a problem with it. `false` if the name is not on screen.
     */
    fun show(
        start: Int,
        end: Int,
        name: String,
        check: (String) -> String?,
        rename: (String) -> Unit,
    ): Boolean {
        val bounds = editor.screenBounds(start, end) ?: return false
        val owner =
            editor.popupOwner
                ?.scene
                ?.window
                ?.takeIf { it.isShowing } ?: return false
        hide()
        onRename = rename
        this.check = check
        field.text = name
        field.prefColumnCount = (name.length + 6).coerceIn(12, 40)
        popup.show(owner, bounds.minX, bounds.maxY)
        popup.sizeToScene()
        val screen =
            Screen.getScreensForRectangle(bounds.minX, bounds.minY, bounds.width, bounds.height).firstOrNull()
                ?: Screen.getPrimary()
        val position = popupPosition(bounds, popup.width, popup.height, screen.visualBounds)
        popup.x = position.x
        popup.y = position.y
        field.requestFocus()
        field.selectAll()
        // Changes only: a window that is not focused when the field opens (no window manager) keeps it.
        watches =
            listOfNotNull(
                owner.xProperty().subscribe { _, _ -> hide() },
                owner.yProperty().subscribe { _, _ -> hide() },
                owner.focusedProperty().subscribe { _, focused -> if (!focused) hide() },
                field.focusedProperty().subscribe { _, focused -> if (!focused) hide() },
                (owner as? Stage)?.iconifiedProperty()?.subscribe { _, iconified -> if (iconified) hide() },
            )
        return true
    }

    /** Hides the field without renaming. */
    fun hide() = popup.hide()

    override fun close() = hide()

    private fun key(event: KeyEvent) {
        when (event.code) {
            KeyCode.ENTER -> {
                event.consume()
                val typed = field.text
                check(typed)?.let { found ->
                    showProblem(found)
                    return
                }
                val rename = onRename
                hide()
                rename(typed)
            }

            KeyCode.ESCAPE -> {
                event.consume()
                hide()
            }

            else -> {}
        }
    }

    private fun showProblem(text: String?) {
        problem.text = text.orEmpty()
        problem.isVisible = text != null
        problem.isManaged = text != null
        if (popup.isShowing) popup.sizeToScene()
    }

    private fun hidden() {
        watches.forEach { it.unsubscribe() }
        watches = emptyList()
        onRename = {}
        check = { null }
        editor.popupOwner?.requestFocus()
    }
}
