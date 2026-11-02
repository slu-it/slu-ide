package dev.sluide.databases.ui.cards

/**
 * Where a dragged card lands when the pointer is at [y]: [midpoints] are the vertical centres of the cards other than
 * the dragged one, top to bottom, in the list's coordinates. The result is the number of them above [y], that is the
 * card's position in the list after it was removed: exactly the `to` of `DatabasesTabModel.move(from, to)`, which
 * removes the card at `from` and inserts it at `to`. 0 above all of them, `midpoints.size` below all (or in an empty
 * list). Equal to the card's current index means no move.
 */
internal fun dropIndex(
    midpoints: List<Double>,
    y: Double,
): Int = midpoints.count { it < y }
