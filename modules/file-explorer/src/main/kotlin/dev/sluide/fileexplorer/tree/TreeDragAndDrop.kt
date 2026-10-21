package dev.sluide.fileexplorer.tree

import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.SnapshotParameters
import javafx.scene.control.ScrollBar
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.DragEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.TransferMode
import javafx.scene.paint.Color
import javafx.util.Duration
import java.nio.file.Path

/** [items] without those at or below another one (by [path]), each path once, in the given order. */
internal fun <T> outermost(
    items: List<T>,
    path: (T) -> Path,
): List<T> {
    val distinct = items.distinctBy(path)
    return distinct.filter { item -> distinct.none { it !== item && path(item).startsWith(path(it)) } }
}

/**
 * Whether entries at [sources] can be moved into the folder [target]: not into one of them or a folder inside one, and
 * not if all of them are directly in [target] already.
 */
internal fun canDrop(
    sources: List<Path>,
    target: Path,
): Boolean = sources.isNotEmpty() && sources.none { target.startsWith(it) } && sources.any { it.parent != target }

/**
 * Moving entries of [tree] by drag and drop onto a folder or the empty area below the rows, which stands for the
 * project folder (ADR 0073). Only drags started in this tree are accepted, through a format of its own: nothing is
 * dragged in from other applications or windows, or out to them. The target is marked as [dropTarget] (the project row
 * for the empty area); a collapsed folder held under the pointer expands, and the tree scrolls while the pointer is
 * near its top or bottom edge. A drop hands the entries and the folder to [move] once the drop has completed, since
 * [move] asks in a dialog that waits; a drag that ends without a drop selects the dragged entries again ([reselect]).
 */
internal class TreeDragAndDrop(
    private val tree: TreeView<Path>,
    private val projectItem: () -> FileTreeItem?,
    /** Whether an entry can be moved: not top-level, not being deleted, not holding an entry being deleted. */
    private val canChange: (FileTreeItem) -> Boolean,
    /** Whether entries may be moved into a folder: not if it is being deleted or lies in an entry being deleted. */
    private val canDropInto: (FileTreeItem) -> Boolean,
    /** The selected entries, in row order. */
    private val selectedEntries: () -> List<FileTreeItem>,
    /** Selects the entries of a drag that ended without a drop, those still shown. */
    private val reselect: (List<FileTreeItem>) -> Unit,
    private val move: (items: List<FileTreeItem>, folder: FileTreeItem) -> Unit,
) {
    private val target = SimpleObjectProperty<TreeItem<Path>?>()

    /** The folder the dragged entries would be moved into if dropped now; `null` while there is none. */
    val dropTarget: ReadOnlyObjectProperty<TreeItem<Path>?> get() = target

    /** The dragged entries, outermost only, in row order; `null` while no drag of this tree runs. */
    private var sources: List<FileTreeItem>? = null

    /** The entries selected for the drag, nested ones included, to select again if it ends without a move. */
    private var selection: List<FileTreeItem> = emptyList()

    private val expandTimer = PauseTransition(EXPAND_DELAY)
    private var expanding: FileTreeItem? = null

    /** Where the pointer was at the last DRAG_OVER, in the scene, and whether it was outside the rows (scroll bars). */
    private var pointer: Point2D? = null
    private var outside = false

    private var rowHeight = DEFAULT_ROW_HEIGHT
    private var scrollDirection = 0
    private val scroller =
        Timeline(KeyFrame(SCROLL_INTERVAL, { scrollStep() })).apply { cycleCount = Animation.INDEFINITE }

    init {
        tree.addEventHandler(MouseEvent.DRAG_DETECTED, ::dragDetected)
        tree.addEventHandler(DragEvent.DRAG_OVER, ::dragOver)
        tree.addEventHandler(DragEvent.DRAG_EXITED) { clearFeedback() }
        tree.addEventHandler(DragEvent.DRAG_DROPPED, ::dragDropped)
        tree.addEventHandler(DragEvent.DRAG_DONE) { event ->
            if (sources == null) return@addEventHandler
            sources = null
            clearFeedback()
            // Not dropped: the selection may have been lost meanwhile, e.g. when a folder above it expanded.
            if (event.transferMode == null) reselect(selection)
        }
    }

    /**
     * Starts dragging the selection if the pressed row is selected, else that row alone (selecting it). Top-level rows
     * and entries that cannot be changed (being deleted or holding an entry being deleted) are not dragged.
     */
    private fun dragDetected(event: MouseEvent) {
        if (!event.isPrimaryButtonDown) return
        val cell = cellAt(event.pickResult.intersectedNode)?.takeUnless { it.isEmpty } ?: return
        val pressed = cell.treeItem as? FileTreeItem ?: return
        val selected = selectedEntries()
        val items =
            if (selected.any { it === pressed }) {
                selected
            } else {
                tree.selectionModel.clearSelection()
                tree.selectionModel.select(pressed)
                listOf(pressed)
            }
        if (items.isEmpty() || !items.all(canChange)) return
        val dragged = outermost(items) { it.value }
        sources = dragged
        selection = items
        val board = tree.startDragAndDrop(TransferMode.MOVE)
        board.setContent(
            ClipboardContent().apply { put(EXPLORER_PATHS, dragged.joinToString("\n") { it.value.toString() }) },
        )
        board.dragView = cell.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        event.consume()
    }

    private fun dragOver(event: DragEvent) {
        if (!isOwn(event)) return
        pointer = Point2D(event.sceneX, event.sceneY)
        val accepted = hover(rowUnder(event.pickResult.intersectedNode))
        if (accepted != null) event.acceptTransferModes(TransferMode.MOVE)
        scrollNear(event.y)
        event.consume()
    }

    private fun dragDropped(event: DragEvent) {
        if (!isOwn(event)) return
        val folder = acceptedFolder(rowUnder(event.pickResult.intersectedNode))
        val dragged = selection
        if (folder != null && sources != null) {
            event.isDropCompleted = true
            // The move asks first, in a dialog with an event loop of its own, which must not run inside the drop.
            // It takes the whole selection, so that a cancelled move can select it again.
            Platform.runLater { move(dragged, folder) }
        } else {
            event.isDropCompleted = false
        }
        clearFeedback()
        event.consume()
    }

    /**
     * Marks the folder a drop on [cell] would move the dragged entries into, expanding it later if it is a collapsed
     * row, and returns it; `null` if there is none.
     */
    private fun hover(cell: TreeCell<*>?): FileTreeItem? {
        cell?.height?.takeIf { it > 0 }?.let { rowHeight = it }
        val accepted = acceptedFolder(cell)
        target.value = accepted
        // Only a folder under the pointer expands, not the project folder standing for the empty area.
        expandLater(accepted?.takeIf { cell != null && !cell.isEmpty && cell.treeItem === it })
        return accepted
    }

    /**
     * The folder a drop on [cell] would move the dragged entries into, if they may be moved there: a folder row, or the
     * project folder for the empty area (no cell or an empty one); none while the pointer is [outside] the rows.
     */
    private fun acceptedFolder(cell: TreeCell<*>?): FileTreeItem? {
        val dragged = sources ?: return null
        if (outside || !dragged.all(canChange)) return null
        val folder =
            if (cell == null || cell.isEmpty) {
                projectItem()
            } else {
                (cell.treeItem as? FileTreeItem)?.takeIf { it.isDirectory }
            }
        return folder?.takeIf { canDrop(dragged.map { it.value }, it.value) && canDropInto(it) }
    }

    /** The row holding [node], and whether [node] is [outside] the rows: on a scroll bar or the corner between them. */
    private fun rowUnder(node: Node?): TreeCell<*>? {
        val path = generateSequence(node) { it.parent }.takeWhile { it !== tree }.toList()
        outside = path.any { it is ScrollBar || "corner" in it.styleClass }
        return path.filterIsInstance<TreeCell<*>>().firstOrNull()
    }

    /** Whether [event] belongs to a drag started in this tree; drags from elsewhere are left alone. */
    private fun isOwn(event: DragEvent): Boolean =
        sources != null && event.gestureSource === tree && event.dragboard.hasContent(EXPLORER_PATHS)

    /** The row holding [node]; `null` for the parts of the tree outside the rows. */
    private fun cellAt(node: Node?): TreeCell<*>? =
        generateSequence(node) { it.parent }.takeWhile { it !== tree }.filterIsInstance<TreeCell<*>>().firstOrNull()

    /** The shown row at [point] in the scene; `null` if none is there. */
    private fun rowAt(
        flow: VirtualFlow<*>,
        point: Point2D,
    ): TreeCell<*>? {
        val first = flow.firstVisibleCell?.index ?: return null
        val last = flow.lastVisibleCell?.index ?: return null
        return (first..last)
            .mapNotNull { flow.getVisibleCell(it) as? TreeCell<*> }
            .firstOrNull { it.isVisible && it.localToScene(it.layoutBounds).contains(point) }
    }

    /** Expands [folder] after a while under the pointer if it is collapsed; another folder or `null` cancels it. */
    private fun expandLater(folder: FileTreeItem?) {
        val collapsed = folder?.takeIf { !it.isExpanded && !it.isLeaf }
        if (collapsed === expanding) return
        expanding = collapsed
        expandTimer.stop()
        if (collapsed != null) {
            expandTimer.setOnFinished {
                // Loaded first: read during the expansion, the folder's rows were counted twice by the tree, which
                // then gave later rows wrong indices.
                collapsed.children
                collapsed.isExpanded = true
                expanding = null
            }
            expandTimer.playFromStart()
        }
    }

    /** Scrolls while [y] (in the tree) is within a row's height of the top or bottom edge, else stops. */
    private fun scrollNear(y: Double) {
        scrollDirection =
            when {
                y < rowHeight -> -1
                y > tree.height - rowHeight -> 1
                else -> 0
            }
        if (scrollDirection == 0) {
            scroller.stop()
        } else if (scroller.status != Animation.Status.RUNNING) {
            scrollStep()
            scroller.play()
        }
    }

    private fun scrollStep() {
        val flow = tree.lookup(".virtual-flow") as? VirtualFlow<*> ?: return
        flow.scrollPixels(scrollDirection * rowHeight / 2)
        // The rows move under the pointer, which may stand still and send no DRAG_OVER.
        val point = pointer ?: return
        if (!outside) hover(rowAt(flow, point))
    }

    private fun clearFeedback() {
        pointer = null
        target.value = null
        expandLater(null)
        scrollDirection = 0
        scroller.stop()
    }
}

/** The dragboard format of entries dragged in the explorer: their paths, one per line. */
private val EXPLORER_PATHS: DataFormat =
    "application/x-sluide-explorer-paths".let { DataFormat.lookupMimeType(it) ?: DataFormat(it) }

/** How long a collapsed folder must stay under the pointer before it expands. */
private val EXPAND_DELAY = Duration.millis(700.0)

/** How often the tree scrolls by half a row while the pointer is near its edge. */
private val SCROLL_INTERVAL = Duration.millis(40.0)

private const val DEFAULT_ROW_HEIGHT = 24.0
