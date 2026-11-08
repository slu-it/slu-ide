package dev.sluide.codeintelligence.binding

import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.TextChange
import javafx.scene.input.KeyEvent

/**
 * Which character the user just typed, so that a feature can tell a typed character from the same text coming from
 * undo, redo, paste or an applied edit (ADR 0147): a text change does not say where it came from. Fed by the key
 * filters [attach] adds to the editor: a key press forgets the last character, the key typed that follows it
 * remembers its own. Shortcut chords type control characters or nothing and are left out, and so are Windows' AltGr
 * characters (Ctrl+Alt) and input method commits (no key typed). FX thread.
 */
internal class TypedKeys {
    private var typed: String? = null

    /** A key was pressed: what it types (if anything) follows as [keyTyped]. */
    fun keyPressed() {
        typed = null
    }

    /** A key typed [character], before the editor inserts it. */
    fun keyTyped(character: String) {
        typed = character
    }

    /**
     * The character typed for [change] if a key typed exactly what it inserted without replacing anything, else
     * `null`; asked once per change, as the next change was not typed by the same key.
     */
    fun take(change: TextChange): String? {
        val character = typed
        typed = null
        return character?.takeIf { change.inserted == it && change.removed.isEmpty() }
    }

    /** Feeds this from [editor]'s key events until the handle is closed. */
    fun attach(editor: ExtensibleCodeEditor): AutoCloseable {
        val pressed = editor.addEventFilter(KeyEvent.KEY_PRESSED) { keyPressed() }
        val typedFilter =
            editor.addEventFilter(KeyEvent.KEY_TYPED) { event ->
                if (!event.isControlDown && !event.isMetaDown) keyTyped(event.character)
            }
        return AutoCloseable {
            typedFilter.unsubscribe()
            pressed.unsubscribe()
        }
    }
}
