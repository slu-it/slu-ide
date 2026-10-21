package dev.sluide.fileexplorer.tree

import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Point2D
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ScrollBar
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.stage.Popup
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
 * Moving entries of [tree] by dragging them with the mouse onto a folder or the empty area below the rows, which stands
 * for the project folder (ADR 0073). A plain mouse drag within the tree, not the platform's drag and drop: nothing is
 * dragged in from or out to other applications or windows, and after a platform drop on Linux the window kept a stale
 * pointer grab, so the confirmation took no clicks.
 *
 * A press on a row and a move beyond [DRAG_THRESHOLD] start the drag: the selection if the pressed row is selected,
 * else that row. The target is marked as [dropTarget] (the project row for the empty area), an image of the row follows
 * the pointer, a collapsed folder held under the pointer expands, and the tree scrolls while the pointer is near its
 * top or bottom edge. Releasing over a target hands the entries and the folder to [move], after the release; releasing
 * elsewhere, Escape, any mouse press, losing the focus or another tree content cancels, selecting the dragged entries
 * again ([reselect]); the rest of that press is kept from the rows (no selection change, click or context menu). A
 * press and release without a drag stay a click: on a selected row of several, the tree selects only that row on the
 * release.
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
    /** Selects the entries of a drag that ended without a move, those still shown. */
    private val reselect: (List<FileTreeItem>) -> Unit,
    private val move: (items: List<FileTreeItem>, folder: FileTreeItem) -> Unit,
) {
    private val target = SimpleObjectProperty<TreeItem<Path>?>()

    /** The folder the dragged entries would be moved into if released now; `null` while there is none. */
    val dropTarget: ReadOnlyObjectProperty<TreeItem<Path>?> get() = target

    /** The press on a row that may start a drag, until the button is released. */
    private var press: Press? = null

    /** The dragged entries, outermost only, in row order; `null` while no drag runs. */
    private var sources: List<FileTreeItem>? = null

    /** The entries selected for the drag, nested ones included, to select again if it ends without a move. */
    private var selection: List<FileTreeItem> = emptyList()

    /** Where the pointer was last, in the scene, while dragging. */
    private var pointer: Point2D? = null

    /** The image of the pressed row following the pointer. */
    private var dragImage: Popup? = null

    /** Set when a drag ends on a release, so the click that may follow it does nothing. */
    private var swallowClick = false

    /** Set when a drag was cancelled with a button down: the press's drags and releases are kept from the rows. */
    private var ignoring = false

    /** Set when a press cancelled a drag, so the context menu it may request does not open. */
    private var swallowMenu = false

    private val expandTimer = PauseTransition(EXPAND_DELAY)
    private var expanding: FileTreeItem? = null

    private var rowHeight = DEFAULT_ROW_HEIGHT
    private var scrollDirection = 0
    private val scroller =
        Timeline(KeyFrame(SCROLL_INTERVAL, { scrollStep() })).apply { cycleCount = Animation.INDEFINITE }

    init {
        // Filters, so a drag's events can be kept from the rows: their selection and clicks.
        tree.addEventFilter(MouseEvent.MOUSE_PRESSED, ::pressed)
        tree.addEventFilter(MouseEvent.MOUSE_DRAGGED, ::dragged)
        tree.addEventFilter(MouseEvent.MOUSE_RELEASED, ::released)
        tree.addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (swallowClick) event.consume()
            swallowClick = false
        }
        tree.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED) { event -> if (swallowMenu) event.consume() }
        tree.addEventFilter(KeyEvent.KEY_PRESSED, ::cancelOnEscape)
        tree.focusedProperty().addListener { _, _, focused -> if (!focused) cancel() }
        // Another folder opened: the dragged entries are no longer shown.
        tree.rootProperty().addListener { _ -> cancel() }
    }

    private class Press(
        val item: FileTreeItem,
        val cell: TreeCell<*>,
        val point: Point2D,
    )

    private fun pressed(event: MouseEvent) {
        if (sources != null) {
            // Any button, also the primary one after a release that never reached the tree.
            cancel()
            swallowMenu = true
            event.consume()
            return
        }
        press = null
        ignoring = false
        swallowClick = false
        swallowMenu = false
        if (event.button != MouseButton.PRIMARY || event.isShiftDown || event.isShortcutDown) return
        val cell = cellAt(event.pickResult.intersectedNode)?.takeUnless { it.isEmpty } ?: return
        val item = cell.treeItem as? FileTreeItem ?: return
        press = Press(item, cell, Point2D(event.sceneX, event.sceneY))
    }

    private fun dragged(event: MouseEvent) {
        if (ignoring) {
            event.consume()
            return
        }
        val start = press ?: return
        val point = Point2D(event.sceneX, event.sceneY)
        if (sources == null) {
            // Kept from the row until the drag starts: on a selected row of several, the tree selects only that row
            // on the release unless the mouse was dragged, which a small jitter of a click must not count as.
            if (start.point.distance(point) < DRAG_THRESHOLD) {
                event.consume()
                return
            }
            if (!startDrag(start, event)) {
                press = null
                return
            }
        }
        event.consume()
        if (sources == null) return
        pointer = point
        dragImage?.apply {
            x = event.screenX + DRAG_IMAGE_OFFSET
            y = event.screenY + DRAG_IMAGE_OFFSET
        }
        hover(spotAt(point))
        scrollNear(tree.sceneToLocal(point).y)
    }

    private fun released(event: MouseEvent) {
        if (ignoring) {
            event.consume()
            swallowClick = true
            if (!event.isPrimaryButtonDown && !event.isSecondaryButtonDown && !event.isMiddleButtonDown) {
                ignoring = false
                press = null
                // A context menu may be requested right after the release (Windows).
                Platform.runLater { swallowMenu = false }
            }
            return
        }
        if (event.button != MouseButton.PRIMARY || press == null) return
        press = null
        if (sources == null) return
        event.consume()
        swallowClick = true
        val folder = hover(spotAt(Point2D(event.sceneX, event.sceneY)))
        val items = selection
        end()
        if (folder != null) {
            // After the release: the move asks in a dialog with an event loop of its own.
            Platform.runLater { move(items, folder) }
        } else {
            reselect(items)
        }
    }

    /**
     * Starts dragging the selection if the pressed row is selected, else that row alone (selecting it); returns whether
     * it did. Top-level rows and entries that cannot be changed are not dragged.
     */
    private fun startDrag(
        start: Press,
        event: MouseEvent,
    ): Boolean {
        val selected = selectedEntries()
        val items =
            if (selected.any { it === start.item }) {
                selected
            } else {
                tree.selectionModel.clearSelection()
                tree.selectionModel.select(start.item)
                listOf(start.item)
            }
        if (items.isEmpty() || !items.all(canChange)) return false
        sources = outermost(items) { it.value }
        selection = items
        // So that losing the focus cancels.
        if (!tree.isFocused) tree.requestFocus()
        showDragImage(start, event)
        return true
    }

    /**
     * Shows the pressed row's icon and name beside the pointer, with "+N" for the other dragged entries, in a window of
     * its own that takes no input. Styled like the explorer (its stylesheets and font, `.explorer-drag-image`), so it is
     * readable in both themes, also when the row is selected.
     */
    private fun showDragImage(
        start: Press,
        event: MouseEvent,
    ) {
        val window = tree.scene?.window ?: return
        val cell = start.cell.takeIf { it.treeItem === start.item } ?: return
        val others = (sources?.size ?: 1) - 1
        val image =
            HBox(
                *listOfNotNull(
                    (cell as? PathCell)?.iconCopy(),
                    Label(cell.text),
                    Label("+$others").takeIf { others > 0 }?.apply { styleClass += "explorer-drag-count" },
                ).toTypedArray(),
            ).apply {
                styleClass += "explorer-drag-image"
                stylesheets.setAll(tree.stylesheets)
                style = tree.style
                opacity = DRAG_IMAGE_OPACITY
            }
        dragImage =
            Popup().apply {
                content += image
                scene.root.isMouseTransparent = true
                // While a popup shows, the window's key events go to it, Escape included, which would only hide it.
                isHideOnEscape = false
                scene.addEventFilter(KeyEvent.KEY_PRESSED, ::cancelOnEscape)
                show(window, event.screenX + DRAG_IMAGE_OFFSET, event.screenY + DRAG_IMAGE_OFFSET)
            }
    }

    private fun cancelOnEscape(event: KeyEvent) {
        if (event.code == KeyCode.ESCAPE && sources != null) {
            cancel()
            event.consume()
        }
    }

    /**
     * Ends a running drag without moving and selects the dragged entries again (those still shown); the rest of the
     * press is kept from the rows.
     */
    private fun cancel() {
        if (sources == null) return
        val items = selection
        end()
        press = null
        ignoring = true
        reselect(items)
    }

    private fun end() {
        sources = null
        selection = emptyList()
        pointer = null
        target.value = null
        expandLater(null)
        scrollDirection = 0
        scroller.stop()
        dragImage?.hide()
        dragImage = null
        tree.cursor = null
    }

    /** What lies under a point of the scene while dragging. */
    private sealed interface Spot {
        /** Outside the tree, or on its scroll bars: no target. */
        object Outside : Spot

        /** Below the rows (or in the tree's frame): the project folder. */
        object Empty : Spot

        class Row(
            val cell: TreeCell<*>,
        ) : Spot
    }

    /** What lies under [point] (in the scene): found by position, since the pressed row receives the drag's events. */
    private fun spotAt(point: Point2D): Spot {
        if (!tree.localToScene(tree.layoutBounds).contains(point)) return Spot.Outside
        val bars = tree.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>() + listOfNotNull(tree.lookup(".corner"))
        if (bars.any { it.isVisible && it.localToScene(it.layoutBounds).contains(point) }) return Spot.Outside
        val flow = flow() ?: return Spot.Empty
        return rowAt(flow, point)?.let { Spot.Row(it) } ?: Spot.Empty
    }

    /**
     * Marks the folder a release at [spot] would move the dragged entries into, expanding it later if it is a
     * collapsed row, and returns it; `null` if there is none.
     */
    private fun hover(spot: Spot): FileTreeItem? {
        val cell = (spot as? Spot.Row)?.cell
        cell?.height?.takeIf { it > 0 }?.let { rowHeight = it }
        val accepted = acceptedFolder(spot)
        target.value = accepted
        tree.cursor = if (accepted != null) Cursor.CLOSED_HAND else Cursor.DEFAULT
        // Only a folder under the pointer expands, not the project folder standing for the empty area.
        expandLater(accepted?.takeIf { cell?.treeItem === it })
        return accepted
    }

    /**
     * The folder a release at [spot] would move the dragged entries into, if they may be moved there: a folder row, or
     * the project folder for the empty area.
     */
    private fun acceptedFolder(spot: Spot): FileTreeItem? {
        val dragged = sources ?: return null
        if (!dragged.all(canChange)) return null
        val folder =
            when (spot) {
                Spot.Outside -> null
                Spot.Empty -> projectItem()
                is Spot.Row -> (spot.cell.treeItem as? FileTreeItem)?.takeIf { it.isDirectory }
            }
        return folder?.takeIf { canDrop(dragged.map { it.value }, it.value) && canDropInto(it) }
    }

    /** The row holding [node]; `null` for the parts of the tree outside the rows. */
    private fun cellAt(node: Node?): TreeCell<*>? =
        generateSequence(node) { it.parent }.takeWhile { it !== tree }.filterIsInstance<TreeCell<*>>().firstOrNull()

    private fun flow(): VirtualFlow<*>? = tree.lookup(".virtual-flow") as? VirtualFlow<*>

    /** The shown row at [point] in the scene; `null` if none is there. */
    private fun rowAt(
        flow: VirtualFlow<*>,
        point: Point2D,
    ): TreeCell<*>? {
        val first = flow.firstVisibleCell?.index ?: return null
        val last = flow.lastVisibleCell?.index ?: return null
        return (first..last)
            .mapNotNull { flow.getVisibleCell(it) as? TreeCell<*> }
            .firstOrNull { it.isVisible && !it.isEmpty && it.localToScene(it.layoutBounds).contains(point) }
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

    /** Scrolls while [y] (in the tree) is within a row's height of the top or bottom edge or beyond, else stops. */
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
        val flow = flow() ?: return
        flow.scrollPixels(scrollDirection * rowHeight / 2)
        // The rows move under the pointer, which may stand still and send no MOUSE_DRAGGED.
        pointer?.let { hover(spotAt(it)) }
    }
}

/** How far the mouse has to move with the button down before entries are dragged. */
private const val DRAG_THRESHOLD = 5.0

/** How far the drag image is off the pointer, right and down, so it never lies under it. */
private const val DRAG_IMAGE_OFFSET = 12.0

private const val DRAG_IMAGE_OPACITY = 0.9

/** How long a collapsed folder must stay under the pointer before it expands. */
private val EXPAND_DELAY = Duration.millis(700.0)

/** How often the tree scrolls by half a row while the pointer is near its edge. */
private val SCROLL_INTERVAL = Duration.millis(40.0)

private const val DEFAULT_ROW_HEIGHT = 24.0
