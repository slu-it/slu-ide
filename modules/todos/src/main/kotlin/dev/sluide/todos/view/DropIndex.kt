package dev.sluide.todos.view

/** The pointer must move this far (px) before a press on a card header becomes a drag. */
internal const val DRAG_THRESHOLD = 5.0

/**
 * Where the dragged card (at index [dragged] of the cards whose centres are [cardCenters], in the list's coordinates,
 * before the drag) lands when the pointer is at [y]: the number of the other cards whose centre is above [y]. That is
 * the card's index in the list once it is moved.
 */
internal fun dropIndex(
    cardCenters: List<Double>,
    dragged: Int,
    y: Double,
): Int = cardCenters.withIndex().count { (index, center) -> index != dragged && center < y }
