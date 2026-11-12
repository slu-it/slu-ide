package dev.sluide.codereview.list

import dev.sluide.codereview.find.FindMatch
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide
import javafx.scene.Node

/** The layer of [LineText] that marks the find's matches. */
internal const val FIND_MATCH_LAYER = "review-find-match"

/** The layer of [LineText] that marks the find's current match (drawn over [FIND_MATCH_LAYER]). */
internal const val FIND_CURRENT_LAYER = "review-find-current"

/** The matches of the review's find, painted into the cells of the rows they are in. */
internal class FindHighlights {
    private var byRow: Map<ReviewRow, List<FindMatch>> = emptyMap()
    private var current: FindMatch? = null

    /** Remembers [matches] (their rows are indices into [rows]) and the match [currentIndex] as the current one. */
    fun set(
        rows: List<ReviewRow>,
        matches: List<FindMatch>,
        currentIndex: Int?,
    ) {
        byRow = matches.groupBy { rows[it.row] }
        current = currentIndex?.let { matches.getOrNull(it) }
    }

    /** Marks the matches in the text of [row]'s [cell]. */
    fun paint(
        row: ReviewRow,
        cell: Node,
    ) {
        val pane = cell as? LineRowPane ?: return
        val matches = byRow[row].orEmpty()
        pane.childrenUnmodifiable.filterIsInstance<LineText>().forEachIndexed { i, text ->
            val side = sideOf(row, i)
            val mine = matches.filter { side == null || it.side == side }
            text.setRanges(FIND_MATCH_LAYER, mine.map { it.columns })
            text.setRanges(FIND_CURRENT_LAYER, mine.filter { it == current }.map { it.columns })
        }
    }

    /** The side the [index]-th text of a row shows; `null` for a unified row, which has one. */
    private fun sideOf(
        row: ReviewRow,
        index: Int,
    ): ReviewSide? =
        when {
            row !is ReviewRow.SplitLine -> null
            index == 0 -> ReviewSide.ORIGINAL
            else -> ReviewSide.CURRENT
        }
}
