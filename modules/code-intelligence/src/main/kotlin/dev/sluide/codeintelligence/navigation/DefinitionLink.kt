package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.StyledRange
import javafx.scene.Cursor
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.util.Subscription

/** The overlay layer and class of the name under the mouse while Shortcut is held. */
internal const val LINK_LAYER = "ci-link"

private val LINK_CSS: String =
    checkNotNull(DefinitionLink::class.java.getResource("definition-link.css")).toExternalForm()

/** What a [LinkModel] needs of the editor. */
internal interface LinkView {
    /** Whether go to definition can be asked now. */
    fun enabled(): Boolean

    /** The word at [offset], `start until end`, `null` for none. */
    fun wordAt(offset: Int): IntRange?

    /** Underlines [range] with the hand cursor, or nothing (`null`) with the text cursor. */
    fun show(range: IntRange?)
}

/**
 * When the name under the mouse shows as a link (ADR 0146): while Shortcut is held, the mouse is over a word and go to
 * definition can be asked; releasing the key, moving off the word, leaving the text or typing ends it. FX thread.
 */
internal class LinkModel(
    private val view: LinkView,
) {
    private var shortcutDown = false
    private var mouseOffset: Int? = null

    /** The word shown as a link, `null` for none. */
    var shown: IntRange? = null
        private set

    /** The mouse moved to the character at [offset] (`null`: none), with Shortcut held or not. */
    fun mouseMoved(
        offset: Int?,
        shortcut: Boolean,
    ) {
        mouseOffset = offset
        shortcutDown = shortcut
        update()
    }

    /** A key was pressed or released; [down] says whether Shortcut is held now. */
    fun keyChanged(down: Boolean) {
        shortcutDown = down
        update()
    }

    /** The mouse left the text. */
    fun mouseExited() {
        mouseOffset = null
        update()
    }

    /** The text changed: the offset under the mouse is unknown until it moves. */
    fun textChanged() {
        mouseOffset = null
        update()
    }

    private fun update() {
        val offset = mouseOffset
        val range = if (shortcutDown && offset != null) view.wordAt(offset)?.takeIf { view.enabled() } else null
        if (range == shown) return
        shown = range
        view.show(range)
    }
}

/**
 * Shortcut+hover and Shortcut+click in one bound editor (ADR 0146): the name under the mouse is underlined with the
 * hand cursor while Shortcut is held ([LinkModel]); a primary Shortcut+click on a name goes to its definition
 * ([go]) instead of moving the caret, its drag and release taken too. On macOS Ctrl+click stays the context menu: Cmd
 * is Shortcut there. FX thread.
 */
internal class DefinitionLink(
    private val binding: DocumentBinding,
    private val go: (Int) -> Unit,
) : AutoCloseable {
    private val editor = binding.editor
    private val model =
        LinkModel(
            object : LinkView {
                override fun enabled() = navigationBlocker(binding, DEFINITION) == null

                override fun wordAt(offset: Int) = editor.wordAt(offset)

                override fun show(range: IntRange?) {
                    editor.overlay(
                        LINK_LAYER,
                        range?.let { listOf(StyledRange(it.first, it.last + 1, LINK_LAYER)) }.orEmpty(),
                    )
                    editor.setTextCursor(if (range == null) null else Cursor.HAND)
                    watchWindowFocus(range != null)
                }
            },
        )

    /** While the link shows: the window losing the focus (Cmd+Tab with Shortcut held) ends it, no release comes. */
    private var windowFocus: Subscription? = null

    /** Whether the mouse gesture under way began with a Shortcut+click this took. */
    private var takingGesture = false

    private val handles: List<AutoCloseable> =
        listOf(editor.addStylesheet(LINK_CSS), editor.onTextChange { model.textChanged() }) +
            listOf(
                editor.addEventFilter(
                    MouseEvent.MOUSE_MOVED,
                ) { model.mouseMoved(editor.offsetAt(it), it.isShortcutDown) },
                editor.addEventFilter(MouseEvent.MOUSE_EXITED) { model.mouseExited() },
                editor.addEventFilter(KeyEvent.KEY_PRESSED) { model.keyChanged(it.isShortcutDown) },
                editor.addEventFilter(KeyEvent.KEY_RELEASED) { model.keyChanged(it.isShortcutDown) },
                editor.addEventFilter(MouseEvent.MOUSE_PRESSED, ::pressed),
                editor.addEventFilter(MouseEvent.MOUSE_DRAGGED) { if (takingGesture) it.consume() },
                editor.addEventFilter(MouseEvent.MOUSE_RELEASED) { if (takingGesture) it.consume() },
                editor.addEventFilter(MouseEvent.MOUSE_CLICKED) { if (takingGesture) it.consume() },
            ).map { subscription -> AutoCloseable { subscription.unsubscribe() } }

    private fun pressed(event: MouseEvent) {
        takingGesture = false
        if (event.button != MouseButton.PRIMARY || !event.isShortcutDown || event.isPopupTrigger) return
        if (navigationBlocker(binding, DEFINITION) != null) return
        // Only on a name, as the underline shows it: a click on `(` or a space stays a click.
        val offset = editor.offsetAt(event)?.takeIf { editor.wordAt(it) != null } ?: return
        takingGesture = true
        event.consume()
        go(offset)
    }

    private fun watchWindowFocus(on: Boolean) {
        windowFocus?.unsubscribe()
        windowFocus = null
        if (!on) return
        val window = editor.popupOwner?.scene?.window ?: return
        windowFocus = window.focusedProperty().subscribe { _, focused -> if (!focused) model.keyChanged(down = false) }
    }

    override fun close() {
        handles.asReversed().forEach { it.close() }
        model.textChanged()
    }
}
