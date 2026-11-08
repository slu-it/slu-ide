package dev.sluide.codeintelligence.ui

/**
 * The list of a [CaretPopup] without its controls: the items and the selected one, moved by the popup's keys. A
 * non-empty list always has a selection. FX thread (or any single thread in tests).
 */
internal class PopupListModel<T> {
    /** The items shown, in order. */
    var items: List<T> = emptyList()
        private set

    /** The selected index; -1 while there are no items. */
    var selectedIndex = -1
        private set

    /** The selected item, `null` while there are no items. */
    val selected: T? get() = items.getOrNull(selectedIndex)

    /**
     * Replaces the items, e.g. filtered while typing: the selected item stays selected if it is still there (by
     * equality), else the first item is.
     */
    fun setItems(newItems: List<T>) {
        val previous = selected
        items = newItems.toList()
        val kept = if (previous == null) -1 else items.indexOf(previous)
        selectedIndex =
            when {
                items.isEmpty() -> -1
                kept >= 0 -> kept
                else -> 0
            }
    }

    /** Selects [index], clamped to the items. */
    fun select(index: Int) {
        if (items.isNotEmpty()) selectedIndex = index.coerceIn(0, items.lastIndex)
    }

    /** Moves the selection by [delta] rows; past either end it wraps around, as a single step (Up, Down) does. */
    fun move(delta: Int) {
        if (items.isEmpty()) return
        selectedIndex = Math.floorMod(selectedIndex + delta, items.size)
    }

    /** Moves the selection by [pages] pages of [pageSize] rows, stopping at the first and last item. */
    fun page(
        pages: Int,
        pageSize: Int,
    ) = select(selectedIndex + pages * pageSize.coerceAtLeast(1))

    /** Selects the first item. */
    fun first() = select(0)

    /** Selects the last item. */
    fun last() = select(items.lastIndex)
}
