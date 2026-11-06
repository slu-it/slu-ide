package dev.sluide.terminal.view

import javafx.application.Platform
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent

/**
 * Puts [text] on the system clipboard, on the FX thread: directly when called there, else later. Used for the
 * selection and for what programs copy with OSC 52, which arrives on the emulator thread.
 */
internal fun copyToClipboard(text: String) {
    if (Platform.isFxApplicationThread()) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
    } else {
        Platform.runLater { copyToClipboard(text) }
    }
}
