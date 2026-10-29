package dev.sluide.fileviewers

import javafx.scene.Node

/**
 * Columns beside the line numbers and change markers of a code view, e.g. icons to run what a line holds. Code views
 * implement it.
 */
interface GutterColumns {
    /**
     * Adds a column [width] pixels wide right of the line numbers, change markers and the columns added before:
     * [column] gives the node to show, centered, in the row of a paragraph (0-based), or `null` for none. Every row
     * has the cell of the column, empty for `null`, so the text stays aligned whichever rows have a node. It is asked
     * whenever a row is created: for the visible paragraphs, again when a paragraph moves (its index changes) or its
     * text changes, and on [refreshGutter]; so keep it cheap.
     */
    fun addGutterColumn(
        width: Double,
        column: (paragraph: Int) -> Node?,
    )

    /** Creates the visible rows of the gutter again, e.g. after what a column shows changed. */
    fun refreshGutter()
}
