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

    /** Whether the user chose the selected item (a click, a key that moves the selection), not the app. */
    private var userChose = false

    /**
     * Replaces the items, e.g. filtered while typing: an item the user chose (a key that moves the selection, a click)
     * stays selected if it is still there (by equality); else, and while the user chose nothing, the first item is
     * selected (the lists come ranked). A choice whose item is gone is forgotten. With [keepIndex] the selected
     * position stays instead (the same places in the same order, only their texts changed), clamped to the new items.
     */
    fun setItems(
        newItems: List<T>,
        keepIndex: Boolean = false,
    ) {
        val previous = selected
        val previousIndex = selectedIndex
        items = newItems.toList()
        val kept =
            if (keepIndex) {
                previousIndex
            } else if (!userChose) {
                -1
            } else {
                items.indexOf(previous)
            }
        if (kept < 0) userChose = false
        selectedIndex =
            when {
                items.isEmpty() -> -1
                kept >= 0 -> kept.coerceAtMost(items.lastIndex)
                else -> 0
            }
    }

    /** Selects [index], clamped to the items, as the user's choice (a click; the moving keys end here too). */
    fun select(index: Int) {
        if (items.isEmpty()) return
        selectedIndex = index.coerceIn(0, items.lastIndex)
        userChose = true
    }

    /** Selects [index] as the app's choice, not the user's: a later [setItems] selects the first item again. */
    fun selectForApp(index: Int) {
        if (items.isEmpty()) return
        selectedIndex = index.coerceIn(0, items.lastIndex)
        userChose = false
    }

    /** Forgets the user's choice, e.g. when the popup hid: the next showing starts with the first item. */
    fun forgetChoice() {
        userChose = false
    }

    /** Moves the selection by [delta] rows; past either end it wraps around, as a single step (Up, Down) does. */
    fun move(delta: Int) {
        if (items.isEmpty()) return
        selectedIndex = Math.floorMod(selectedIndex + delta, items.size)
        userChose = true
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
