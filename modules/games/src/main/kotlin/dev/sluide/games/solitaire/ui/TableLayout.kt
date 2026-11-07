package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.Variant
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A rectangle in the table's coordinates. */
internal data class Rect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
) {
    fun contains(
        px: Double,
        py: Double,
    ): Boolean = px >= x && px < x + w && py >= y && py < y + h

    fun union(other: Rect): Rect {
        val left = min(x, other.x)
        val top = min(y, other.y)
        return Rect(left, top, max(x + w, other.x + other.w) - left, max(y + h, other.y + other.h) - top)
    }
}

/** A card to draw at [rect]: the [index]-th card of [pile], bottom to top. */
internal data class Placed(
    val pile: PileId,
    val index: Int,
    val card: Card,
    val faceUp: Boolean,
    val rect: Rect,
)

/**
 * Where everything is: a [slot][slots] for every pile, the [cards] to draw (bottom to top, so list order is drawing
 * order) and the size the table needs: [prefWidth]/[prefHeight] exceed the given size when it is too small.
 */
internal data class Layout(
    val cardWidth: Double,
    val cardHeight: Double,
    val slots: Map<PileId, Rect>,
    val cards: List<Placed>,
    val prefWidth: Double,
    val prefHeight: Double,
)

private const val MIN_CARD_WIDTH = 48.0
private const val MAX_CARD_WIDTH = 160.0
private const val GAP = 0.12
private const val EDGE = 8.0
private const val ROW_GAP = 0.15
private const val FAN = 0.22
private const val DOWN_OVERLAP = 0.12
private const val UP_OVERLAP = 0.28
private const val MIN_SQUEEZE = 0.5

private fun columnCount(table: Table): Int =
    when (table.mode.variant) {
        Variant.KLONDIKE -> 7
        Variant.FREECELL -> 8
        Variant.SPIDER -> 10
    }

/** The column of the top row where the piles of [kind] start, per variant. */
private fun topColumn(
    variant: Variant,
    id: PileId,
): Int =
    when (variant) {
        Variant.KLONDIKE -> {
            when (id.kind) {
                PileKind.STOCK -> 0
                PileKind.WASTE -> 1
                else -> 3 + id.index
            }
        }

        Variant.FREECELL -> {
            if (id.kind == PileKind.CELL) id.index else 4 + id.index
        }

        Variant.SPIDER -> {
            if (id.kind == PileKind.STOCK) 0 else 2 + id.index
        }
    }

/** The distance from the top edge of the first card to the top edge of the last at the normal overlaps. */
private fun nominalExtent(
    pile: Pile,
    cardHeight: Double,
): Double {
    var sum = 0.0
    for (i in 0 until pile.cards.size - 1) {
        sum += cardHeight * if (i < pile.faceDown) DOWN_OVERLAP else UP_OVERLAP
    }
    return sum
}

/**
 * The layout of [table] in [width] x [height]: columns across the width (card width 48..160 px), the top row, and the
 * tableau below it with overlaps that squeeze when a column would pass [height].
 */
internal fun layout(
    table: Table,
    width: Double,
    height: Double,
): Layout {
    val variant = table.mode.variant
    val columns = columnCount(table)
    val raw = (width - 2 * EDGE) / (columns * (1 + GAP))
    val w = floor(raw.coerceIn(MIN_CARD_WIDTH, MAX_CARD_WIDTH))
    val h = w * CardPainter.ASPECT
    val gap = w * GAP
    val margin = gap / 2 + EDGE
    val needed = 2 * margin + columns * w + (columns - 1) * gap
    val left = if (width > needed) (width - needed) / 2 else 0.0

    fun colX(column: Int) = left + margin + column * (w + gap)

    val topY = EDGE
    val tableauY = topY + h + h * ROW_GAP + EDGE
    val slots = LinkedHashMap<PileId, Rect>()
    val cards = ArrayList<Placed>()

    val topRow =
        table.piles.keys.filter { it.kind != PileKind.TABLEAU }.sortedWith(
            compareBy({ it.kind }, { it.index }),
        )
    for (id in topRow) {
        val rect = Rect(colX(topColumn(variant, id)), topY, w, h)
        slots[id] = rect
        val pile = table.pile(id)
        val size = pile.cards.size
        if (size == 0) continue

        fun placed(
            index: Int,
            rect: Rect,
        ) = Placed(id, index, pile.cards[index], index >= pile.faceDown, rect)
        if (id.kind == PileKind.WASTE) {
            val spread = min(max(table.fan, 1), size)
            if (size > spread) cards += placed(size - spread - 1, rect)
            for (k in 0 until spread) cards += placed(size - spread + k, rect.copy(x = rect.x + k * FAN * w))
        } else {
            cards += placed(size - 1, rect)
        }
    }

    val tableau = table.ids(PileKind.TABLEAU)
    val available = height - tableauY - EDGE - h
    val worst = tableau.maxOfOrNull { nominalExtent(table.pile(it), h) } ?: 0.0
    val squeeze = if (worst <= 0.0 || worst <= available) 1.0 else (available / worst).coerceIn(MIN_SQUEEZE, 1.0)
    for ((column, id) in tableau.withIndex()) {
        val rect = Rect(colX(column), tableauY, w, h)
        slots[id] = rect
        val pile = table.pile(id)
        var y = tableauY
        pile.cards.forEachIndexed { index, card ->
            val up = index >= pile.faceDown
            cards += Placed(id, index, card, up, rect.copy(y = y))
            y += h * squeeze * if (up) UP_OVERLAP else DOWN_OVERLAP
        }
    }
    val extent = tableauY + h + worst * squeeze + EDGE
    return Layout(w, h, slots, cards, max(width, needed), max(height, extent))
}
