package dev.sluide.speech.ui

import dev.sluide.fileviewers.StyledRange
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent

/**
 * The view logic shared by every place that reads aloud (the tab, a code editor): what of [ReadAloudUi] goes to the
 * [surface], and what the keys and a double-click do. [play] is what Play means for that surface. The caller owns the
 * rest (text replacement, toolbar, notice, activity) and consumes an event when a handler returns true.
 *
 * Keys: while editable, Shortcut+Enter plays; while locked, Space pauses or resumes and Escape stops.
 */
internal class ReadingBinding(
    private val model: ReadAloudModel,
    private val surface: ReadingSurface,
    private val play: () -> Unit,
) {
    private var appliedOverlay = emptyList<StyledRange>()
    private var appliedReveal: IntRange? = null

    /** Applies [ui] to the surface; the overlay and the reveal only when they changed. */
    fun apply(ui: ReadAloudUi) {
        surface.editable = ui.editable
        if (ui.overlay != appliedOverlay) {
            appliedOverlay = ui.overlay
            surface.overlay(ui.overlay)
        }
        if (ui.reveal != appliedReveal) {
            appliedReveal = ui.reveal
            ui.reveal?.let { surface.reveal(it.first, it.last + 1) }
        }
    }

    /** The text was replaced: the next [apply] sends the overlay and the reveal again. */
    fun resetApplied() {
        appliedOverlay = emptyList()
        appliedReveal = null
    }

    /** Returns whether the key was handled. */
    fun onKey(event: KeyEvent): Boolean =
        if (model.ui.editable) {
            (event.code == KeyCode.ENTER && event.isShortcutDown).also { if (it) play() }
        } else if (event.isShortcutDown || event.isAltDown || event.isShiftDown) {
            false
        } else if (event.code == KeyCode.SPACE) {
            model.togglePause()
            true
        } else if (event.code == KeyCode.ESCAPE) {
            model.stop()
            true
        } else {
            false
        }

    /** A double-click on locked text jumps to the sentence under it: the word it selected starts inside it. */
    fun onDoubleClick() {
        if (!model.ui.editable) model.jumpTo(surface.selection?.first ?: surface.caretOffset)
    }
}
