package dev.sluide.databases.ui.console

import dev.sluide.databases.sql.Completion
import dev.sluide.databases.sql.CompletionResult

/**
 * What the completion popup shows and which item is chosen; no toolkit. [update] takes each recomputed result and
 * decides whether the popup stays open: it closes on an empty list and when the caret left the word the popup was
 * opened for (before its start, or the word's start moved, e.g. after a space); otherwise it keeps the chosen label.
 */
internal class CompletionModel {
    var items: List<Completion> = emptyList()
        private set

    /** The index of the chosen item, -1 while closed. */
    var selected = -1
        private set

    var replaceFrom = 0
        private set

    var replaceTo = 0
        private set

    val isOpen: Boolean get() = items.isNotEmpty()

    val chosen: Completion? get() = items.getOrNull(selected)

    /**
     * Takes a freshly computed [result] for the caret at [caret]; [opening] is true for Ctrl+Space or an automatic
     * open, which accept any word start. Returns whether the popup is open afterwards.
     */
    fun update(
        result: CompletionResult,
        caret: Int,
        opening: Boolean,
    ): Boolean {
        val wasOpen = isOpen
        val keep = chosen?.label
        val moved = wasOpen && !opening && (caret < replaceFrom || result.replaceFrom != replaceFrom)
        if (result.items.isEmpty() || moved) {
            close()
            return false
        }
        items = result.items
        replaceFrom = result.replaceFrom
        replaceTo = result.replaceTo
        selected = (if (wasOpen && keep != null) items.indexOfFirst { it.label == keep } else -1).coerceAtLeast(0)
        return true
    }

    fun close() {
        items = emptyList()
        selected = -1
    }

    /** Moves the choice by [delta] rows, wrapping around at both ends. */
    fun move(delta: Int) {
        if (items.isEmpty()) return
        selected = Math.floorMod(selected + delta, items.size)
    }

    /** Moves the choice by [delta] rows and stops at the ends, for PageUp and PageDown. */
    fun page(delta: Int) {
        if (items.isEmpty()) return
        selected = (selected + delta).coerceIn(0, items.size - 1)
    }

    /** Chooses the row [index] (a click). */
    fun select(index: Int) {
        if (index in items.indices) selected = index
    }

    companion object {
        /**
         * Whether the change from [old] to [new] is a single character typed at [caret] (the caret right behind it),
         * as opposed to a paste, a deletion or a completion; never while text is [selectionLength] selected.
         */
        fun isTypedChar(
            old: String,
            new: String,
            caret: Int,
            selectionLength: Int = 0,
        ): Boolean {
            if (selectionLength > 0 || new.length != old.length + 1 || caret < 1 || caret > new.length) return false
            return new.regionMatches(0, old, 0, caret - 1) &&
                new.regionMatches(caret, old, caret - 1, old.length - caret + 1)
        }
    }
}
