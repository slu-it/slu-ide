package dev.sluide.fileviewers

import javafx.event.Event
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import org.testfx.api.FxRobot

/** Whether Shortcut is Cmd (macOS) rather than Ctrl. */
private val shortcutIsMeta = System.getProperty("os.name").startsWith("Mac")

/**
 * Fires a key press of [code] with the given modifiers at [target], as if it had the focus. Synthetic OS input is
 * avoided (docs/testing.md).
 */
internal fun FxRobot.press(
    target: Node,
    code: KeyCode,
    shortcut: Boolean = false,
    shift: Boolean = false,
    alt: Boolean = false,
) {
    interact { Event.fireEvent(target, keyPress(code, shift, shortcut = shortcut, alt = alt)) }
}

/** A key press of [code] with the given modifiers. */
internal fun keyPress(
    code: KeyCode,
    shift: Boolean = false,
    shortcut: Boolean = true,
    alt: Boolean = false,
): KeyEvent =
    KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !shortcutIsMeta, alt, shortcut && shortcutIsMeta)
