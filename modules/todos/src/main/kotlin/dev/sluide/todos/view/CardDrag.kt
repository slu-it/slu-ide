package dev.sluide.todos.view

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
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlin.math.abs

private const val EDGE = 24.0
private const val SCROLL_STEP = 12.0
private const val SCROLL_INTERVAL_MS = 30.0

/**
 * Reorders the root cards of [box] (in [scroll]) by a plain mouse drag on a card's header, not by platform drag and drop
 * (which leaves a stale pointer grab on Linux, ADR 0073). The dragged card follows the pointer; on release [move] gets
 * its new index, which counts the root cards and folders. Cards inside folders are not dragged yet. Escape cancels;
 * near the edges the scroll pane scrolls.
 */
internal class CardDrag(
    private val scroll: ScrollPane,
    private val box: VBox,
    private val move: (id: String, toIndex: Int) -> Unit,
) {
    private class Press(
        val sceneY: Double,
        val grabY: Double,
    )

    private class Active(
        val card: TodoCard,
        val grabY: Double,
        val index: Int,
        val centers: List<Double>,
        val items: List<Region>,
    ) {
        var dropIndex = index
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

    /** Makes the header of [card] start drags. */
    fun attach(card: TodoCard) {
        card.header.setOnMousePressed { event ->
            if (event.button == MouseButton.PRIMARY && !onButton(event.target)) {
                press = Press(event.sceneY, box.sceneToLocal(0.0, event.sceneY).y)
            }
        }
        card.header.setOnMouseDragged { event ->
            val pressed = press ?: return@setOnMouseDragged
            if (active == null && abs(event.sceneY - pressed.sceneY) > DRAG_THRESHOLD) start(card, pressed)
            if (active?.card === card) follow(event.sceneY)
        }
        card.header.setOnMouseReleased { event ->
            if (event.button != MouseButton.PRIMARY) return@setOnMouseReleased
            val current = active
            end()
            if (current != null && current.card === card && current.dropIndex != current.index) {
                move(card.todo.id, current.dropIndex)
            }
        }
    }

    private fun onButton(target: Any?): Boolean =
        generateSequence(target as? Node) { it.parent }.any { it is ButtonBase }

    private fun start(
        card: TodoCard,
        pressed: Press,
    ) {
        // The root items: cards and folders; a card inside a folder is not draggable yet.
        val items = box.children.filter { it is TodoCard || it is FolderView }.filterIsInstance<Region>()
        val index = items.indexOf(card)
        if (index < 0) return
        active = Active(card, pressed.grabY, index, items.map { it.layoutY + it.height / 2 }, items)
        card.styleClass += "dragging"
        card.viewOrder = -1.0
        scene = card.scene?.also { it.addEventFilter(KeyEvent.KEY_PRESSED, escape) }
    }

    private fun follow(sceneY: Double) {
        val drag = active ?: return
        lastSceneY = sceneY
        val pointer = box.sceneToLocal(0.0, sceneY).y
        drag.card.translateY = pointer - drag.grabY
        // By the pointer, not the dragged card's centre: a tall expanded card would otherwise have to travel far.
        drag.dropIndex = dropIndex(drag.centers, drag.index, pointer)
        makeRoom(drag)
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

    /** Shifts the items between the dragged card's place and the drop place by one card, to show where it lands. */
    private fun makeRoom(drag: Active) {
        val shift = drag.card.height + box.spacing
        drag.items.forEachIndexed { index, item ->
            if (item === drag.card) return@forEachIndexed
            item.translateY =
                when {
                    index in (drag.index + 1)..drag.dropIndex -> -shift
                    index in drag.dropIndex until drag.index -> shift
                    else -> 0.0
                }
        }
    }

    private fun scrollStep() {
        val range = box.height - scroll.viewportBounds.height
        if (range <= 0 || direction == 0) return
        scroll.vvalue = (scroll.vvalue + direction * SCROLL_STEP / range).coerceIn(0.0, 1.0)
        follow(lastSceneY)
    }

    /** Ends a drag in progress without moving anything and stops following the scene. */
    fun cancel() = end()

    /** Ends the drag without moving anything; also the cleanup after a drop. */
    private fun end() {
        autoScroll.stop()
        direction = 0
        scene?.removeEventFilter(KeyEvent.KEY_PRESSED, escape)
        scene = null
        active?.let { drag ->
            drag.items.forEach { it.translateY = 0.0 }
            drag.card.styleClass -= "dragging"
            drag.card.viewOrder = 0.0
        }
        active = null
        press = null
    }
}
