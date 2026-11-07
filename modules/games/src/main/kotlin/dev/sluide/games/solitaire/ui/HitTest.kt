package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import kotlin.math.abs

/** The topmost card drawn under the point, or null. */
internal fun cardAt(
    layout: Layout,
    x: Double,
    y: Double,
): Placed? = layout.cards.lastOrNull { it.rect.contains(x, y) }

/**
 * The pile a drop at the point belongs to: the one whose slot or cards (for a column extended down to the bottom of
 * the table) contain it; below the top row the nearest column by x; otherwise (also outside the layout) null.
 */
internal fun pileAt(
    layout: Layout,
    x: Double,
    y: Double,
): PileId? {
    val areas =
        layout.slots.map { (id, slot) ->
            val cards = layout.cards.filter { it.pile == id }
            var area = cards.fold(slot) { acc, placed -> acc.union(placed.rect) }
            if (id.kind == PileKind.TABLEAU) area = area.copy(h = maxOf(area.h, layout.prefHeight - area.y))
            id to area
        }
    areas.firstOrNull { (_, area) -> area.contains(x, y) }?.let { return it.first }
    if (x !in 0.0..layout.prefWidth || y !in 0.0..layout.prefHeight) return null
    val columns = layout.slots.filterKeys { it.kind == PileKind.TABLEAU }
    val bandTop = columns.values.minOfOrNull { it.y } ?: return null
    if (y < bandTop) return null
    return columns.minByOrNull { (_, slot) -> abs(slot.x + slot.w / 2 - x) }?.key
}
