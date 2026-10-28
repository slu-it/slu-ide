package dev.sluide.todos.view

import dev.sluide.todos.TodoPlace
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.event.EventHandler
import javafx.geometry.Bounds
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

/** The pointer must move this far (px) before a press on a header becomes a drag. */
private const val DRAG_THRESHOLD = 5.0

private const val EDGE = 24.0
private const val SCROLL_STEP = 12.0
private const val SCROLL_INTERVAL_MS = 30.0
private const val LINE_HEIGHT = 2.0
private const val DROP_TARGET = "todo-drop-target"

/**
 * Moves TODOs and folders of [box] (in [scroll]) by a plain mouse drag on their header, not by platform drag and drop
 * (which leaves a stale pointer grab on Linux, ADR 0073). A TODO, at the root or in a folder, goes between root items,
 * between the cards of an expanded folder, to the top of a collapsed folder or into an empty one; a folder only between
 * root items ([dropTarget]). The dragged item follows the pointer, slightly transparent; [line], an unmanaged child of
 * [box]'s parent, or a highlighted folder header or hint shows where it lands. On release [move] gets the item's id and
 * place, unless that is where it is. Escape cancels; near the edges the scroll pane scrolls; presses on buttons are
 * not drags.
 */
internal class ItemDrag(
    private val scroll: ScrollPane,
    private val box: VBox,
    private val line: Region,
    private val move: (id: String, place: TodoPlace) -> Unit,
) {
    private class Press(
        val sceneY: Double,
        val grabY: Double,
    )

    private class Active(
        val node: Region,
        val id: String,
        val isFolder: Boolean,
        val grabY: Double,
        val rows: List<DropRow>,
        val bounds: List<FolderBounds>,
        val folders: Map<String, FolderView>,
        /** The folder of a dragged card in a folder, raised with it. */
        val raised: FolderView?,
    ) {
        var result: DropResult? = null
    }

    private var press: Press? = null
    private var active: Active? = null
    private var highlighted: Node? = null
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
        line.styleClass += "todo-drop-line"
        line.isManaged = false
        line.isMouseTransparent = true
        line.isVisible = false
    }

    /** Makes the header of [card] start drags of its TODO. */
    fun attach(card: TodoCard) = attach(card.header, card, false) { card.todo.id }

    /** Makes the header of [folder] (not its buttons) start drags of the folder. */
    fun attach(folder: FolderView) = attach(folder.header, folder, true) { folder.folder.id }

    private fun attach(
        header: Node,
        node: Region,
        isFolder: Boolean,
        id: () -> String,
    ) {
        header.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY && !onButton(event.target)) {
                press = Press(event.sceneY, box.sceneToLocal(0.0, event.sceneY).y)
            }
        }
        header.addEventHandler(MouseEvent.MOUSE_DRAGGED) { event ->
            val pressed = press ?: return@addEventHandler
            if (active == null && abs(event.sceneY - pressed.sceneY) > DRAG_THRESHOLD) {
                start(node, id(), isFolder, pressed)
            }
            if (active?.node === node) follow(event.sceneY)
        }
        header.addEventHandler(MouseEvent.MOUSE_RELEASED) { event ->
            if (event.button != MouseButton.PRIMARY) return@addEventHandler
            val current = active
            end()
            val result = current?.result
            if (current?.node === node && result != null) move(current.id, result.place)
        }
    }

    private fun onButton(target: Any?): Boolean =
        generateSequence(target as? Node) { it.parent }.any { it is ButtonBase }

    /** [node]'s layout bounds in [box]'s coordinates. */
    private fun bounds(node: Node): Bounds = box.sceneToLocal(node.localToScene(node.layoutBounds))

    private fun start(
        node: Region,
        id: String,
        isFolder: Boolean,
        pressed: Press,
    ) {
        val rows = mutableListOf<DropRow>()
        val bounds = mutableListOf<FolderBounds>()
        val folders = box.children.filterIsInstance<FolderView>().associateBy { it.folder.id }
        for (child in box.children) {
            if (child is TodoCard) rows += row(RowKind.ROOT_CARD, child.todo.id, null, child)
            if (child !is FolderView) continue
            val folderId = child.folder.id
            val whole = bounds(child)
            bounds += FolderBounds(folderId, whole.minY, whole.maxY)
            val collapsed = child.folder.collapsed
            rows += row(RowKind.FOLDER_HEADER, folderId, folderId, child.header).copy(collapsed = collapsed)
            if (!collapsed) rows += folderRows(child)
        }
        val raised = if (isFolder) null else folders.values.firstOrNull { node in it.cards }
        active = Active(node, id, isFolder, pressed.grabY, rows, bounds, folders, raised)
        node.styleClass += "dragging"
        node.viewOrder = -1.0
        raised?.viewOrder = -1.0
        scene = node.scene?.also { it.addEventFilter(KeyEvent.KEY_PRESSED, escape) }
    }

    private fun folderRows(folder: FolderView): List<DropRow> =
        folder.body.children.mapNotNull {
            when (it) {
                is TodoCard -> row(RowKind.FOLDER_CARD, it.todo.id, folder.folder.id, it)
                folder.emptyHint -> row(RowKind.FOLDER_EMPTY, folder.folder.id, folder.folder.id, it)
                else -> null
            }
        }

    private fun row(
        kind: RowKind,
        id: String,
        folderId: String?,
        node: Node,
    ): DropRow {
        val b = bounds(node)
        return DropRow(kind, id, folderId, b.minY, b.maxY)
    }

    private fun follow(sceneY: Double) {
        val drag = active ?: return
        lastSceneY = sceneY
        val pointer = box.sceneToLocal(0.0, sceneY).y
        drag.node.translateY = pointer - drag.grabY
        drag.result = dropTarget(drag.rows, drag.bounds, drag.id, drag.isFolder, pointer)
        show(drag)
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

    /** Shows the indicator of the current drop target: the insertion line or a highlighted header or hint. */
    private fun show(drag: Active) {
        val indicator = drag.result?.indicator
        val target =
            (indicator as? DropIndicator.Highlight)?.let { highlight ->
                drag.folders[highlight.folderId]?.let { if (highlight.hint) it.emptyHint else it.header }
            }
        if (target !== highlighted) {
            highlighted?.styleClass?.remove(DROP_TARGET)
            target?.styleClass?.add(DROP_TARGET)
            highlighted = target
        }
        line.isVisible = indicator is DropIndicator.Line
        if (indicator !is DropIndicator.Line) return
        val folder = indicator.folderId?.let { drag.folders[it] }
        val (left, right) =
            if (folder == null) {
                box.insets.left to box.width - box.insets.right
            } else {
                bounds(folder.body).minX + folder.body.insets.left to bounds(folder.body).maxX
            }
        val at = box.localToParent(left, indicator.y - LINE_HEIGHT / 2)
        line.resizeRelocate(at.x, at.y, right - left, LINE_HEIGHT)
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
            drag.node.translateY = 0.0
            drag.node.styleClass -= "dragging"
            drag.node.viewOrder = 0.0
            drag.raised?.viewOrder = 0.0
        }
        highlighted?.styleClass?.remove(DROP_TARGET)
        highlighted = null
        line.isVisible = false
        active = null
        press = null
    }
}
