package dev.sluide.databases.ui.cards

import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ButtonBase
import javafx.scene.control.ScrollPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlin.math.abs

/** The pointer must move this far (px) before a press on a card becomes a drag. */
private const val DRAG_THRESHOLD = 5.0

private const val EDGE = 24.0
private const val SCROLL_STEP = 12.0
private const val SCROLL_INTERVAL_MS = 30.0
private const val LINE_HEIGHT = 2.0

/**
 * Reorders the cards of [box] (in [scroll]) by a plain mouse drag, not platform drag and drop (which leaves a stale
 * pointer grab on Linux, ADR 0073), like the TODO cards' drag (ADR 0103) but flat. The dragged card follows the
 * pointer; [line], an unmanaged child of a parent of [scroll], shows where it lands ([dropIndex]). On release [move]
 * gets the card's index and its index after removal, unless that is where it is. Escape cancels; near the edges the
 * scroll pane scrolls; presses on buttons are not drags.
 */
internal class CardDrag(
    private val scroll: ScrollPane,
    private val box: VBox,
    private val line: Region,
    private val move: (from: Int, to: Int) -> Unit,
) {
    private class Press(
        val sceneY: Double,
        val grabY: Double,
    )

    private class Active(
        val node: Region,
        val from: Int,
        val grabY: Double,
        val tops: List<Double>,
        val bottoms: List<Double>,
    ) {
        var to = from
    }

    private var press: Press? = null
    private var active: Active? = null
    private var lastSceneY = 0.0
    private var direction = 0
    private var scene: Scene? = null

    private val autoScroll =
        Timeline(KeyFrame(Duration.millis(SCROLL_INTERVAL_MS), { scrollStep() })).apply {
            cycleCount = Animation.INDEFINITE
        }

    private val escape =
        EventHandler<KeyEvent> {
            if (it.code == KeyCode.ESCAPE) {
                end()
                it.consume()
            }
        }

    init {
        line.styleClass += "db-card-drop-line"
        line.isManaged = false
        line.isMouseTransparent = true
        line.isVisible = false
    }

    /** Makes [card] start drags of itself. */
    fun attach(card: Region) {
        card.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY && !onButton(event.target)) {
                press = Press(event.sceneY, box.sceneToLocal(0.0, event.sceneY).y)
            }
        }
        card.addEventHandler(MouseEvent.MOUSE_DRAGGED) { event ->
            val pressed = press ?: return@addEventHandler
            if (active == null && abs(event.sceneY - pressed.sceneY) > DRAG_THRESHOLD) start(card, pressed)
            if (active?.node === card) follow(event.sceneY)
        }
        card.addEventHandler(MouseEvent.MOUSE_RELEASED) { event ->
            if (event.button != MouseButton.PRIMARY) return@addEventHandler
            val current = active
            end()
            if (current?.node === card && current.to != current.from) move(current.from, current.to)
        }
    }

    private fun onButton(target: Any?): Boolean =
        generateSequence(target as? Node) { it.parent }.any { it is ButtonBase }

    private fun start(
        card: Region,
        pressed: Press,
    ) {
        val from = box.children.indexOf(card)
        if (from < 0) return
        val others = box.children.filter { it !== card }
        active =
            Active(
                card,
                from,
                pressed.grabY,
                others.map { it.boundsInParent.minY },
                others.map { it.boundsInParent.maxY },
            )
        card.styleClass += "dragging"
        card.viewOrder = -1.0
        scene = card.scene?.also { it.addEventFilter(KeyEvent.KEY_PRESSED, escape) }
    }

    private fun follow(sceneY: Double) {
        val drag = active ?: return
        lastSceneY = sceneY
        val pointer = box.sceneToLocal(0.0, sceneY).y
        drag.node.translateY = pointer - drag.grabY
        drag.to = dropIndex(drag.tops.zip(drag.bottoms) { top, bottom -> (top + bottom) / 2 }, pointer)
        showLine(drag)
        val local = scroll.sceneToLocal(0.0, sceneY).y
        val next =
            when {
                local < EDGE -> -1
                local > scroll.height - EDGE -> 1
                else -> 0
            }
        if (next == direction) return
        direction = next
        if (next == 0) autoScroll.stop() else autoScroll.play()
    }

    private fun showLine(drag: Active) {
        val host = line.parent
        line.isVisible = host != null && drag.to != drag.from
        if (!line.isVisible || host == null) return
        val above = drag.bottoms.getOrNull(drag.to - 1)
        val below = drag.tops.getOrNull(drag.to)
        val y =
            when {
                above != null && below != null -> (above + below) / 2
                else -> below ?: above ?: 0.0
            }
        val left = host.sceneToLocal(box.localToScene(box.insets.left, y - LINE_HEIGHT / 2))
        val width = box.width - box.insets.left - box.insets.right
        line.resizeRelocate(left.x, left.y, width, LINE_HEIGHT)
    }

    private fun scrollStep() {
        val range = box.height - scroll.viewportBounds.height
        if (range <= 0 || direction == 0) return
        scroll.vvalue = (scroll.vvalue + direction * SCROLL_STEP / range).coerceIn(0.0, 1.0)
        follow(lastSceneY)
    }

    /** Ends a drag in progress without moving anything and stops following the scene. */
    fun cancel() = end()

    private fun end() {
        autoScroll.stop()
        direction = 0
        scene?.removeEventFilter(KeyEvent.KEY_PRESSED, escape)
        scene = null
        active?.let { drag ->
            drag.node.translateY = 0.0
            drag.node.styleClass -= "dragging"
            drag.node.viewOrder = 0.0
        }
        line.isVisible = false
        active = null
        press = null
    }
}
