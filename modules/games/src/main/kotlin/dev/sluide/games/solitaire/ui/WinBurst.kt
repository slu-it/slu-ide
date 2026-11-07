package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Table
import javafx.animation.AnimationTimer
import javafx.scene.Node
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import javafx.scene.shape.Rectangle
import kotlin.random.Random

/** A card that flies in the win burst and where its foundation is. */
internal data class BurstCard(
    val card: Card,
    val origin: Rect,
)

/** The cards of the foundations in flying order: the top card of each foundation in turn until none is left. */
internal fun burstCards(
    table: Table,
    layout: Layout,
): List<BurstCard> {
    val piles =
        table.ids(PileKind.FOUNDATION).mapNotNull { id ->
            layout.slots[id]?.let { slot -> table.pile(id).cards.asReversed() to slot }
        }
    val depth = piles.maxOfOrNull { it.first.size } ?: 0
    return (0 until depth).flatMap { round ->
        piles.mapNotNull { (cards, slot) -> cards.getOrNull(round)?.let { BurstCard(it, slot) } }
    }
}

/**
 * The win animation: a mouse-transparent [overlay] over the table on which the foundations' cards fly one by one
 * ([BurstPhysics]); each card is one moving node, no copies are left behind. [stop] ends it at once. Time-driven by an
 * [AnimationTimer]. Our own effect, not a copy of any other game's.
 */
internal class WinBurst(
    private val overlay: Pane,
    private val random: Random = Random.Default,
    private val onEnd: () -> Unit = {},
) {
    private class Flying(
        val node: ImageView,
        var particle: Particle,
    )

    private val flying = mutableListOf<Flying>()
    private var pending = ArrayDeque<Pair<BurstCard, ImageView>>()
    private var launched = 0
    private var total = 0
    private var elapsed = 0.0
    private var interval = LAUNCH_MS / 1000.0
    private var last = 0L
    private var floorY = 0.0
    private val timer =
        object : AnimationTimer() {
            override fun handle(now: Long) = tick(now)
        }

    init {
        overlay.isMouseTransparent = true
        overlay.clip =
            Rectangle().apply {
                widthProperty().bind(overlay.widthProperty())
                heightProperty().bind(overlay.heightProperty())
            }
    }

    val isRunning: Boolean get() = total > 0

    /** Starts flying [cards] from the places of the foundations of [source] (the table view). */
    fun start(
        cards: List<Pair<BurstCard, ImageView>>,
        source: Node,
        cardHeight: Double,
    ) {
        stop()
        if (cards.isEmpty()) return
        pending = ArrayDeque(cards)
        total = cards.size
        launched = 0
        elapsed = 0.0
        last = 0L
        interval = minOf(LAUNCH_MS, SPREAD_MS / total) / 1000.0
        floorY = overlay.height - cardHeight
        this.source = source
        timer.start()
    }

    private var source: Node? = null

    private fun originOf(
        rect: Rect,
        source: Node,
    ): Pair<Double, Double> {
        val local = overlay.sceneToLocal(source.localToScene(rect.x, rect.y)) ?: return rect.x to rect.y
        return local.x to local.y
    }

    /** Ends the burst: the flying cards disappear. */
    fun stop() {
        timer.stop()
        overlay.children.clear()
        flying.clear()
        pending.clear()
        val wasRunning = total > 0
        total = 0
        if (wasRunning) onEnd()
    }

    private fun tick(now: Long) {
        val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(MAX_DT)
        last = now
        elapsed += dt
        while (pending.isNotEmpty() && elapsed >= launched * interval) {
            val (card, node) = pending.removeFirst()
            val particle = BurstPhysics.launch(originOf(card.origin, checkNotNull(source)), random)
            flying += Flying(node, particle)
            overlay.children += node
            launched++
        }
        val iterator = flying.iterator()
        while (iterator.hasNext()) {
            val f = iterator.next()
            f.particle = BurstPhysics.step(f.particle, dt, floorY)
            if (BurstPhysics.isGone(f.particle)) {
                overlay.children -= f.node
                iterator.remove()
                continue
            }
            f.node.relocate(f.particle.x, f.particle.y)
            f.node.opacity = f.particle.alpha
        }
        if (pending.isEmpty() && flying.isEmpty()) stop()
    }

    private companion object {
        /** Milliseconds between two launches, at most; many cards launch faster so that all are out in [SPREAD_MS]. */
        const val LAUNCH_MS = 40.0
        const val SPREAD_MS = 2000.0
        const val MAX_DT = 0.05
    }
}
