package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.MoveChooser
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import javafx.animation.TranslateTransition
import javafx.scene.Node
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.util.Duration
import kotlin.math.hypot

private const val DRAG_THRESHOLD = 4.0

/**
 * Mouse play of a [TableView]. A press on a face-up card that is part of a movable run picks up that card and the
 * ones on it. Moving more than 4 px drags the group; releasing over a pile plays the transfer if it is legal, else the
 * group slides back and nothing is played. Without movement it is a click: the chosen move of [MoveChooser.best] is
 * played, or [TableHost.stockClicked] on the stock.
 *
 * A click prefers foundations, so the first click of a double-click already sends a card to a foundation if it can;
 * the second click ([MouseEvent.getClickCount] of 2 or more) on a card is therefore ignored. It still may start a
 * drag. Clicks on the stock are never ignored: quick draws count up as double-clicks. A click on a foundation card
 * does nothing: cards come back from a foundation only by dragging.
 */
internal class TableDrag(
    private val view: TableView,
    private val host: TableHost,
    private val slideBack: Duration,
) {
    private class Press(
        val pile: PileId?,
        val count: Int,
        val x: Double,
        val y: Double,
        val onStock: Boolean,
        val ignoreClick: Boolean,
    ) {
        /** Moved past the threshold: not a click any more. */
        var moved = false
    }

    private var press: Press? = null
    private var dragged: List<Node>? = null

    fun install() {
        view.addEventHandler(MouseEvent.MOUSE_PRESSED, ::pressed)
        view.addEventHandler(MouseEvent.MOUSE_DRAGGED, ::dragged)
        view.addEventHandler(MouseEvent.MOUSE_RELEASED, ::released)
    }

    /** Puts the dragged group above everything again (after highlights were added). */
    fun raise() {
        dragged?.forEach { it.toFront() }
    }

    /** Forgets a press or drag in progress (the view redraws). */
    fun cancel() {
        press = null
        dragged = null
    }

    private fun pressed(event: MouseEvent) {
        if (dragged != null) return
        press = null
        if (!view.inputEnabled || event.button != MouseButton.PRIMARY) return
        val layout = view.layoutNow()
        val stock = PileId(PileKind.STOCK)
        val ignore = event.clickCount >= 2
        if (layout.slots[stock]?.contains(event.x, event.y) == true) {
            press = Press(null, 0, event.x, event.y, true, false)
            return
        }
        val placed = cardAt(layout, event.x, event.y) ?: return
        val table = view.shown
        val size = table.pile(placed.pile).cards.size
        // only face-up cards are part of a run, so this also refuses face-down cards
        if (placed.index < size - host.rules.runLength(table, placed.pile)) return
        press = Press(placed.pile, size - placed.index, event.x, event.y, false, ignore)
    }

    private fun dragged(event: MouseEvent) {
        val p = press ?: return
        val dx = event.x - p.x
        val dy = event.y - p.y
        if (hypot(dx, dy) > DRAG_THRESHOLD) p.moved = true
        val pile = p.pile ?: return
        if (dragged == null) {
            if (!p.moved) return
            val nodes = view.nodesOf(pile, p.count)
            if (nodes.isEmpty()) return
            highlightTargets(pile, p.count)
            nodes.forEach { it.toFront() }
            dragged = nodes
        }
        dragged?.forEach {
            it.translateX = dx
            it.translateY = dy
        }
    }

    private fun highlightTargets(
        pile: PileId,
        count: Int,
    ) {
        val table = view.shown
        for (id in table.piles.keys) {
            if (!host.rules.canMove(table, Move.Transfer(pile, id, count))) continue
            view.areaOf(id, 1)?.let { view.addOverlay(it, "sol-drop-ok") }
        }
    }

    private fun released(event: MouseEvent) {
        val p = press ?: return
        press = null
        val nodes = dragged
        dragged = null
        if (nodes == null) {
            if (!p.moved && !p.ignoreClick && view.inputEnabled) click(p)
            return
        }
        view.children.removeAll { "sol-drop-ok" in it.styleClass }
        val pile = checkNotNull(p.pile)
        val move = if (view.inputEnabled) dropMove(p, pile, event) else null
        if (move != null && host.play(move)) return
        slideBack(nodes)
    }

    private fun dropMove(
        p: Press,
        pile: PileId,
        event: MouseEvent,
    ): Move? {
        val layout = view.layoutNow()
        // the group-centre fallback is only for the gaps of the view; a release outside it cancels
        val inside = event.x in 0.0..layout.prefWidth && event.y in 0.0..layout.prefHeight
        val target =
            if (inside) pileAt(layout, event.x, event.y) ?: groupCentreTarget(layout, p, pile, event) else null
        if (target == null || target == pile) return null
        val move = Move.Transfer(pile, target, p.count)
        return move.takeIf { host.rules.canMove(view.shown, it) }
    }

    /** The pile under the centre of the dragged group's first card, moved by the mouse. */
    private fun groupCentreTarget(
        layout: Layout,
        p: Press,
        pile: PileId,
        event: MouseEvent,
    ): PileId? {
        val first =
            layout.cards
                .filter { it.pile == pile }
                .takeLast(p.count)
                .firstOrNull() ?: return null
        val cx = first.rect.x + first.rect.w / 2 + event.x - p.x
        val cy = first.rect.y + first.rect.h / 2 + event.y - p.y
        return pileAt(layout, cx, cy)
    }

    private fun click(p: Press) {
        if (p.onStock) {
            host.stockClicked()
            return
        }
        val pile = p.pile ?: return
        if (pile.kind == PileKind.FOUNDATION) return
        MoveChooser.best(host.rules, view.shown, pile, p.count)?.let { host.play(it) }
    }

    private fun slideBack(nodes: List<Node>) {
        for (node in nodes) {
            if (slideBack.toMillis() <= 0.0) {
                node.translateX = 0.0
                node.translateY = 0.0
                continue
            }
            TranslateTransition(slideBack, node)
                .apply {
                    toX = 0.0
                    toY = 0.0
                }.play()
        }
    }
}
