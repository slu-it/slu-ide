package dev.sluide.fileviewers

import javafx.scene.Node

/**
 * Columns beside the line numbers and change markers of a code view, e.g. icons to run what a line holds. Code views
 * implement it.
 */
interface GutterColumns {
    /**
     * Adds a column right of the line numbers, change markers and the columns added before: [column] gives the node
     * to show in the row of a paragraph (0-based), or `null` for none. It is asked whenever a row is created: for the
     * visible paragraphs, again when a paragraph moves (its index changes) and on [refreshGutter].
     */
    fun addGutterColumn(column: (paragraph: Int) -> Node?)

    /** Creates the visible rows of the gutter again, e.g. after what a column shows changed. */
    fun refreshGutter()
}
