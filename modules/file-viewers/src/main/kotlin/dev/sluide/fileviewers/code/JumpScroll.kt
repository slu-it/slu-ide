package dev.sluide.fileviewers.code

import javafx.application.Platform
import javafx.beans.InvalidationListener
import org.fxmisc.richtext.CodeArea

/**
 * Jumps of a code view's [area] ("go to definition", Go to Line, opening a file at a line): the caret moves to the
 * target, and if the target line (a paragraph, all its rows with soft wrap) is not fully shown, a line cut at either
 * edge is not, the area scrolls so that the line is its first shown line; a line in view does not scroll vertically,
 * the caret is only kept in view horizontally. Near the end of the text RichTextFX clamps the scroll. The view has one
 * instance, so that a jump drops the pending scroll of any other. FX thread.
 *
 * A view that is not in a scene with a height yet scrolls once it is: the area is laid out, which uses up the
 * follow-caret request pending since its start. A fresh view (never laid out, a tab just opened) has no cells in that
 * first layout, so the request places the line a pixel above the top and would show the line before it: it is put at
 * its initial position (the top of the text), the area is laid out again, and the rule above applies to that position.
 * A view that was laid out and only left its scene keeps its position, and the rule applies there. [cancel] or the
 * next jump drops the wait.
 */
internal class JumpScroll(
    private val area: CodeArea,
) {
    private var waiting: InvalidationListener? = null

    /** Counts jumps and cancels, so that a scroll already queued after the layout knows it is outdated. */
    private var epoch = 0

    /** Moves the caret to [offset] (kept within the text) and shows its line as described above. */
    fun jump(offset: Int) {
        cancel()
        area.moveTo(offset.coerceIn(0, area.length))
        if (laidOut()) {
            area.layout()
            scroll()
            return
        }
        val jumpEpoch = epoch
        val fresh = area.height <= 0 || area.virtualFlow() == null
        val listener =
            InvalidationListener {
                if (laidOut()) {
                    removeListener()
                    // Not inside the layout pass that gave the area its height.
                    Platform.runLater {
                        if (epoch == jumpEpoch) {
                            area.layout()
                            if (fresh) {
                                area.showParagraphAtTop(0)
                                area.layout()
                            }
                            scroll()
                        }
                    }
                }
            }
        waiting = listener
        area.heightProperty().addListener(listener)
        area.sceneProperty().addListener(listener)
        area.requestFollowCaret()
    }

    /** Drops a scroll that waits for the layout. */
    fun cancel() {
        epoch++
        removeListener()
    }

    private fun laidOut() = area.scene != null && area.height > 0 && area.virtualFlow() != null

    private fun removeListener() {
        waiting?.let {
            area.heightProperty().removeListener(it)
            area.sceneProperty().removeListener(it)
        }
        waiting = null
    }

    private fun scroll() {
        val line = area.currentParagraph
        if (isFullyShown(line)) {
            area.requestFollowCaret()
        } else {
            area.showParagraphAtTop(line)
            area.layout()
        }
    }

    /** Whether the cell of [line] lies inside the flow's viewport (the cell's own bounds, not the clamped ones). */
    private fun isFullyShown(line: Int): Boolean {
        val flow = area.virtualFlow() ?: return false
        val cell = flow.getCellIfVisible(line).orElse(null) ?: return false
        val top = cell.node.layoutY + cell.node.layoutBounds.minY
        val bottom = cell.node.layoutY + cell.node.layoutBounds.maxY
        return top >= -TOLERANCE && bottom <= flow.height + TOLERANCE
    }

    private companion object {
        const val TOLERANCE = 0.5
    }
}
