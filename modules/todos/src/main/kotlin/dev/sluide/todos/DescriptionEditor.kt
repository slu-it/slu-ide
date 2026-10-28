package dev.sluide.todos

import javafx.scene.Node

/** A field that edits a TODO's Markdown description; the window supplies a code editor (CodeEditors.text). */
interface DescriptionEditor {
    /** The control to show in the TODO dialog. */
    val node: Node

    /** The current text. */
    val text: String

    /** Gives the field the keyboard focus. */
    fun focus()
}
