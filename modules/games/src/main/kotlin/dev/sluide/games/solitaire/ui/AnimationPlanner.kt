package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Table

/**
 * The card [index] of [pile] (a node of the new layout) starts at [from] and slides to its place after [delayMs];
 * with [flipAfter] it shows its back until it has arrived and then turns over.
 */
internal data class SlidePlan(
    val pile: PileId,
    val index: Int,
    val from: Rect,
    val delayMs: Double = 0.0,
    val flipAfter: Boolean = false,
)

/** The card [index] of [pile] turns over to face up ([toFaceUp]) or face down. */
internal data class FlipPlan(
    val pile: PileId,
    val index: Int,
    val toFaceUp: Boolean,
)

internal data class AnimationPlan(
    val slides: List<SlidePlan>,
    val flips: List<FlipPlan>,
)

/** Delay between the cards of a Spider row and of a Draw 3. */
internal const val STAGGER_MS = 30.0

private val STOCK = PileId(PileKind.STOCK)

/**
 * What moves on the way from [old] ([oldLayout]) to [new] ([newLayout]) by [move] (FLIP: the nodes are already in their
 * new place and start displaced). For a forward step [old] is the step's before table; for [reverse] (undo) [old] is
 * the outcome and [new] the before table. Cards that stay in place but whose position changed (the waste fan, a
 * squeezed column) slide too. [flipped] and [completed] are the outcome's.
 */
internal fun planStep(
    move: Move,
    flipped: List<PileId>,
    completed: List<PileId>,
    old: Table,
    oldLayout: Layout,
    new: Table,
    newLayout: Layout,
    reverse: Boolean,
): AnimationPlan {
    val oldRects = oldLayout.cards.associateBy { it.pile to it.index }
    val newKeys = newLayout.cards.map { it.pile to it.index }.toSet()
    val slides = LinkedHashMap<Pair<PileId, Int>, SlidePlan>()
    for (placed in newLayout.cards) {
        val before = oldRects[placed.pile to placed.index]
        if (before != null && before.card == placed.card && before.rect != placed.rect) {
            slides[placed.pile to placed.index] = SlidePlan(placed.pile, placed.index, before.rect)
        }
    }

    fun put(plan: SlidePlan) {
        if (plan.pile to plan.index in newKeys) slides[plan.pile to plan.index] = plan
    }
    val stockRect = oldLayout.slots[STOCK]
    when (move) {
        is Move.Transfer -> {
            val source = if (reverse) move.to else move.from
            val target = if (reverse) move.from else move.to
            val firstFrom = old.pile(source).cards.size - move.count
            val firstTo = new.pile(target).cards.size - move.count
            val runFoundation = if (reverse && move.to in completed) filledFoundation(old, new) else null
            // a forward transfer that completed a run has nothing to slide: the cards left for the foundation
            if (reverse || move.to !in completed) {
                for (k in 0 until move.count) {
                    val rect =
                        if (runFoundation != null) {
                            oldLayout.slots[runFoundation]
                        } else {
                            oldRects[source to firstFrom + k]?.rect ?: oldLayout.slots[source]
                        }
                    if (rect != null) put(SlidePlan(target, firstTo + k, rect))
                }
            }
            if (runFoundation != null) {
                // the rest of the run comes back from the foundation too
                for (index in old.pile(move.to).cards.size until new.pile(move.to).cards.size) {
                    oldLayout.slots[runFoundation]?.let { put(SlidePlan(move.to, index, it)) }
                }
            }
        }

        Move.Draw -> {
            if (!reverse && stockRect != null) {
                val waste = PileId(PileKind.WASTE)
                for (k in old.pile(waste).cards.size until new.pile(waste).cards.size) {
                    put(SlidePlan(waste, k, stockRect, (k - old.pile(waste).cards.size) * STAGGER_MS))
                }
            }
        }

        Move.DealRow -> {
            if (!reverse && stockRect != null) {
                for ((i, id) in new.ids(PileKind.TABLEAU).withIndex()) {
                    if (id !in completed && new.pile(id).cards.size > old.pile(id).cards.size) {
                        put(SlidePlan(id, new.pile(id).cards.size - 1, stockRect, i * STAGGER_MS))
                    }
                }
            }
        }

        Move.Recycle -> {}
    }
    if (!reverse) putCompletedRuns(move, completed, old, oldRects, new, ::put)
    val flips =
        flipped.mapNotNull { id ->
            val pile = new.pile(id)
            val index = if (reverse) pile.faceDown - 1 else pile.cards.size - 1
            FlipPlan(id, index, !reverse).takeIf { index >= 0 && id to index in newKeys }
        }
    return AnimationPlan(slides.values.toList(), flips)
}

/** The foundation that was empty and now has cards (Spider's run). */
private fun filledFoundation(
    fuller: Table,
    emptier: Table,
): PileId? =
    fuller.ids(PileKind.FOUNDATION).firstOrNull {
        fuller.pile(it).cards.size > emptier.pile(it).cards.size
    }

/** The foundation card of each completed run starts where the run's King was. */
private fun putCompletedRuns(
    move: Move,
    completed: List<PileId>,
    old: Table,
    oldRects: Map<Pair<PileId, Int>, Placed>,
    new: Table,
    put: (SlidePlan) -> Unit,
) {
    val foundations =
        new
            .ids(PileKind.FOUNDATION)
            .filter { new.pile(it).cards.size > old.pile(it).cards.size }
    for ((column, foundation) in completed.zip(foundations)) {
        val oldSize = old.pile(column).cards.size
        val kingIndex = new.pile(column).cards.size
        val added = kingIndex - oldSize
        val rect =
            oldRects[column to kingIndex]?.rect
                ?: (move as? Move.Transfer)?.takeIf { column == it.to && added in 0 until it.count }?.let {
                    oldRects[it.from to old.pile(it.from).cards.size - it.count + added]?.rect
                }
        if (rect != null) put(SlidePlan(foundation, shownIndex(new, foundation), rect))
    }
}

/**
 * The deal: every card of the tableau starts at the stock slot ([origin]; the top-left corner when there is none) and
 * the cards follow one another, round by round, so the whole deal takes [totalMs]; face-up cards flip when they have
 * arrived. The cards of the stock stay where they are.
 */
internal fun planDeal(
    layout: Layout,
    totalMs: Double = DEAL_TOTAL_MS,
    slideMs: Double = DEAL_SLIDE_MS,
): AnimationPlan {
    val origin = layout.slots[STOCK] ?: Rect(0.0, 0.0, layout.cardWidth, layout.cardHeight)
    val cards =
        layout.cards
            .filter { it.pile.kind == PileKind.TABLEAU }
            .sortedWith(compareBy({ it.index }, { it.pile.index }))
    val step = if (cards.size > 1) (totalMs - slideMs).coerceAtLeast(0.0) / (cards.size - 1) else 0.0
    return AnimationPlan(
        cards.mapIndexed { i, placed -> SlidePlan(placed.pile, placed.index, origin, i * step, placed.faceUp) },
        emptyList(),
    )
}

internal const val DEAL_TOTAL_MS = 1000.0
internal const val DEAL_SLIDE_MS = 250.0
