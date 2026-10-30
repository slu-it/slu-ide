package dev.sluide.codecs.ui

import javafx.scene.control.TextArea

/** A text area that knows whether the text change it is notifying about comes from a paste. */
internal class PasteAwareTextArea : TextArea() {
    /** True while a paste changes the text, so a text listener can tell it from typing. */
    var pasting: Boolean = false
        private set

    override fun paste() {
        pasting = true
        try {
            super.paste()
        } finally {
            pasting = false
        }
    }
}
