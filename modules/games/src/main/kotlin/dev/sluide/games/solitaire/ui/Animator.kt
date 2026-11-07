package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Step
import javafx.animation.Animation
import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.ParallelTransition
import javafx.animation.ScaleTransition
import javafx.animation.SequentialTransition
import javafx.animation.Timeline
import javafx.animation.TranslateTransition
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.util.Duration

private const val SLIDE_MS = 150.0
private const val AUTO_SLIDE_MS = 80.0
private const val FLIP_MS = 120.0

/**
 * Plays what [planStep] and [planDeal] plan on the nodes the [view] has just built for the new table: each moving node
 * is displaced to where its card was (FLIP) and slides back to zero; turned-over cards scale their width to 0, swap
 * the side and scale back. Every duration is multiplied by [speed]; 0 plays nothing. Whatever is running is completed
 * (every node in its place) before something new starts and before the view rebuilds its nodes. [onIdle] is called when
 * the deal cascade ends, by itself or skipped.
 */
internal class Animator(
    private val view: TableView,
    private val speed: Double = 1.0,
    private val onIdle: () -> Unit = {},
) {
    private var group: ParallelTransition? = null
    private val resets = mutableListOf<() -> Unit>()
    private val afterSlides = mutableListOf<() -> Unit>()

    /** The deal cascade is running: the table takes no input and a click or key skips it. */
    var cascading = false
        private set

    init {
        view.beforeRebuild = { finish() }
    }

    /** The slide of [step]; [reverse] for an undo, [auto] for the paced moves of auto-finish. */
    fun step(
        step: Step,
        reverse: Boolean,
        auto: Boolean = false,
    ) {
        finish()
        if (speed <= 0.0) return
        val old = if (reverse) step.outcome.table else step.before
        val new = if (reverse) step.before else step.outcome.table
        val oldLayout = layout(old, view.width, view.height)
        var plan =
            planStep(
                step.move,
                step.outcome.flipped,
                step.outcome.completedRuns,
                old,
                oldLayout,
                new,
                view.layoutNow(),
                reverse,
            )
        val move = step.move
        val offset = view.dropOffset
        if (offset != null && !reverse && move is Move.Transfer) {
            val first = new.pile(move.to).cards.size - move.count
            plan =
                plan.copy(
                    slides =
                        plan.slides.map {
                            if (it.pile == move.to && it.index >= first) {
                                it.copy(
                                    from = it.from.copy(x = it.from.x + offset.first, y = it.from.y + offset.second),
                                )
                            } else {
                                it
                            }
                        },
                )
        }
        play(plan, if (auto) AUTO_SLIDE_MS else SLIDE_MS)
    }

    /** The deal cascade of the table shown now. */
    fun deal() {
        finish()
        if (speed <= 0.0) return
        cascading = true
        play(planDeal(view.layoutNow()), DEAL_SLIDE_MS)
        if (group == null) cascading = false
    }

    /** Completes everything that runs: every node is in its place, every card shows its side. */
    fun finish() {
        val running = group ?: return
        group = null
        running.stop()
        complete()
    }

    /** Runs [action] when the slides have ended (now when none is running). */
    fun afterSlides(action: () -> Unit) {
        if (group == null) action() else afterSlides += action
    }

    private fun complete() {
        resets.forEach { it() }
        resets.clear()
        group = null
        val actions = afterSlides.toList()
        afterSlides.clear()
        actions.forEach { it() }
        if (cascading) {
            cascading = false
            onIdle()
        }
    }

    private fun ms(value: Double) = Duration.millis(value * speed)

    private fun play(
        plan: AnimationPlan,
        slideMs: Double,
    ) {
        val cards = view.layoutNow().cards.associateBy { it.pile to it.index }
        val images = view.cardImages()
        val parts = mutableListOf<Animation>()
        val lifted = mutableListOf<ImageView>()
        for (slide in plan.slides.sortedBy { it.index }) {
            val node = view.nodeAt(slide.pile, slide.index) ?: continue
            val place = cards[slide.pile to slide.index] ?: continue
            val shown = checkNotNull(node.image)
            val sequence = mutableListOf<Animation>()
            val dx = slide.from.x - place.rect.x
            val dy = slide.from.y - place.rect.y
            if (dx != 0.0 || dy != 0.0) {
                node.translateX = dx
                node.translateY = dy
                lifted += node
                sequence +=
                    TranslateTransition(ms(slideMs), node).apply {
                        fromX = dx
                        fromY = dy
                        toX = 0.0
                        toY = 0.0
                        delay = ms(slide.delayMs)
                        interpolator = Interpolator.EASE_OUT
                    }
            }
            if (slide.flipAfter) {
                node.image = images.back()
                sequence += flip(node, shown)
            }
            register(node, shown)
            parts += if (sequence.size == 1) sequence.single() else SequentialTransition(*sequence.toTypedArray())
        }
        for (flip in plan.flips) {
            val node = view.nodeAt(flip.pile, flip.index) ?: continue
            val place = cards[flip.pile to flip.index] ?: continue
            val shown = checkNotNull(node.image)
            node.image = if (flip.toFaceUp) images.back() else images.face(place.card)
            register(node, shown)
            parts += flip(node, shown)
        }
        if (parts.isEmpty()) return
        lift(lifted)
        val running = ParallelTransition(*parts.toTypedArray())
        group = running
        running.setOnFinished { if (group === running) complete() }
        running.play()
    }

    /**
     * Puts the sliding [nodes] above every card (top-row nodes come before the tableau in the child list) but below
     * the outlines, in index order; completing puts them back at their former child index.
     */
    private fun lift(nodes: List<ImageView>) {
        if (nodes.isEmpty()) return
        val children = view.children
        // each node with the sibling before it, so that it can go back behind that one whatever happens meanwhile
        val former = nodes.sortedBy { children.indexOf(it) }.map { it to children.getOrNull(children.indexOf(it) - 1) }
        children.removeAll(nodes)
        val above = children.indexOfLast { it is ImageView || "sol-slot" in it.styleClass } + 1
        children.addAll(above, nodes)
        resets +=
            {
                for ((node, before) in former) {
                    children.remove(node)
                    children.add(before?.let { children.indexOf(it) + 1 } ?: 0, node)
                }
            }
    }

    private fun register(
        node: ImageView,
        shown: Image,
    ) {
        resets +=
            {
                node.translateX = 0.0
                node.translateY = 0.0
                node.scaleX = 1.0
                node.image = shown
            }
    }

    /** Scales [node] to nothing, shows [after] and scales it back. */
    private fun flip(
        node: ImageView,
        after: Image,
    ): Animation =
        SequentialTransition(
            ScaleTransition(ms(FLIP_MS / 2), node).apply {
                fromX = 1.0
                toX = 0.0
            },
            Timeline(KeyFrame(Duration.millis(1.0), { node.image = after })),
            ScaleTransition(ms(FLIP_MS / 2), node).apply {
                fromX = 0.0
                toX = 1.0
            },
        )
}
