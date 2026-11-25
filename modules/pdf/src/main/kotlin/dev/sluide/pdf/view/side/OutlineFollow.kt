package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.OutlineItem

/**
 * Which outline item the side panel selects for the current page (ADR 0192): the [outline] flattened in document
 * order, each item with its parent. Items are told apart by identity, as two can be equal. FX-free.
 */
internal class OutlineFollow(
    outline: List<OutlineItem>,
) {
    /** An item of the outline and the entry of its parent, `null` at the top level. */
    class Entry(
        val item: OutlineItem,
        val parent: Entry?,
    )

    /** The items in document order: each parent before its children. */
    val entries: List<Entry> = buildList { addOutline(outline, parent = null) }

    /**
     * The item to select on [page]: of the items whose target lies on it or before it, the one whose target page is
     * the latest, and of several on that page the last in document order (so a child on its parent's page wins);
     * `null` when no target lies on or before [page]. When that item is hidden under a collapsed parent ([expanded]
     * says which are expanded), its nearest shown ancestor instead: following never expands.
     */
    fun itemFor(
        page: Int,
        expanded: (OutlineItem) -> Boolean,
    ): OutlineItem? {
        var found: Entry? = null
        var foundPage = -1
        for (entry in entries) {
            val target = entry.item.target?.page ?: continue
            if (target in foundPage..page) {
                found = entry
                foundPage = target
            }
        }
        // The outermost collapsed ancestor, if any: everything below it is hidden.
        var shown = found ?: return null
        var ancestor = shown.parent
        while (ancestor != null) {
            if (!expanded(ancestor.item)) shown = ancestor
            ancestor = ancestor.parent
        }
        return shown.item
    }
}

/** Adds [items] and their children (the outline is at most 100 levels deep, ADR 0192), each after its parent. */
private fun MutableList<OutlineFollow.Entry>.addOutline(
    items: List<OutlineItem>,
    parent: OutlineFollow.Entry?,
) {
    for (item in items) {
        val entry = OutlineFollow.Entry(item, parent)
        add(entry)
        addOutline(item.children, entry)
    }
}
