package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.TextDiff
import javafx.beans.property.ReadOnlyStringProperty
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.PlainTextChange
import org.fxmisc.richtext.model.TwoDimensional
import org.fxmisc.undo.UndoManager

/**
 * Shows the change markers of a [CodeView] in its [gutter] and moves them along with the unsaved edits of [area]: the
 * diff's markers apply to the [savedText]; back at it (e.g. after undoing, by [undoManager]'s mark), they are the
 * diff's again. While [deferred] (the conflict banner is shown), a diff waits for [showPending]. FX thread.
 */
internal class ChangeMarkerFollower(
    private val area: CodeArea,
    private val gutter: Gutter,
    private val undoManager: UndoManager<*>,
    private val savedText: ReadOnlyStringProperty,
    private val deferred: () -> Boolean,
) {
    private val changeMarkers = ChangeMarkers(savedText.get())

    /** The diff given to [show] while [deferred]. */
    private var pendingChanges: PendingChanges? = null

    private class PendingChanges(
        val diff: TextDiff?,
    )

    init {
        area.multiPlainChanges().subscribe(::moveChangeMarkers)
        // Undoing reaches the saved text only after the undone change was reported.
        undoManager.atMarkedPositionProperty().addListener { _, _, atMark -> if (atMark) resetChangeMarkers() }
        savedText.addListener { _, _, saved -> changeMarkers.saved(saved, area.text) }
    }

    /**
     * Shows the markers of [diff] (of the saved text; `null`: unsaved edits only), unless [deferred]: then it waits
     * until [showPending], when the file's new text has become the saved one.
     */
    fun show(diff: TextDiff?) {
        if (deferred()) {
            pendingChanges = PendingChanges(diff)
            return
        }
        pendingChanges = null
        changeMarkers.show(diff, savedText.get(), area.text)
        gutter.markers = changeMarkers.markers
        // Without a diff, the markers show unsaved edits only, and there is no diff to open.
        gutter.markersClickable = diff != null
    }

    /** Shows the diff that waited while [deferred], if any. */
    fun showPending() {
        pendingChanges?.let { show(it.diff) }
    }

    /**
     * Moves the change markers along with [changes]: replaced lines become modified, inserted ones added. Of several
     * changes at once, each is seen in the text as it was before it. Back at the saved text (e.g. after undoing), the
     * markers are the diff's again.
     */
    private fun moveChangeMarkers(changes: List<PlainTextChange>) {
        val change = changes.singleOrNull()
        if (change == null) {
            // Each change's position is in the text after the ones before it: undo them all, then redo one by one.
            var text = area.text
            for (c in changes.asReversed()) {
                text =
                    text.replaceRange(c.position, c.position + c.inserted.length, c.removed)
            }
            for (c in changes) {
                changeMarkers.edited(LineEdit.at(text, c.position, c.removed, c.inserted))
                text = text.replaceRange(c.position, c.position + c.removed.length, c.inserted)
            }
        } else {
            val start = change.position
            // The text before the change and after it is the same as before.
            val end = start + change.inserted.length
            changeMarkers.edited(
                LineEdit.of(
                    line = area.offsetToPosition(start, TwoDimensional.Bias.Forward).major,
                    startsLine = start == 0 || area.getText(start - 1, start) == "\n",
                    endsLine = end == area.length || area.getText(end, end + 1) == "\n",
                    removed = change.removed,
                    inserted = change.inserted,
                ),
            )
        }
        if (undoManager.isAtMarkedPosition) changeMarkers.resetIfUnedited(area.text)
        gutter.markers = changeMarkers.markers
    }

    private fun resetChangeMarkers() {
        changeMarkers.resetIfUnedited(area.text)
        gutter.markers = changeMarkers.markers
    }
}
