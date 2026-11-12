package dev.sluide.codereview.list

import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide
import javafx.collections.ObservableList
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import java.nio.file.Files

/** What the interactions need from the list that shows the rows. */
internal interface InteractionHost {
    /** The node the context menus belong to. */
    val owner: Node

    /** The indices of the rows that are shown, empty while nothing is laid out. */
    fun visibleIndices(): IntRange

    /** The node showing row [index], `null` while it is not shown. */
    fun cell(index: Int): Node?

    /** The row [node] is the cell of (the sticky header's cell included), -1 when it is none. */
    fun indexOfCell(node: Node): Int

    /** The first row fully below the sticky header. */
    fun viewportRow(): Int

    /** The row the list will show first, as [ReviewDiffView.firstVisible]. */
    fun firstVisibleRow(): Int

    fun reveal(index: Int)

    fun setViewed(
        path: String,
        viewed: Boolean,
    )
}

private const val SELECTED = "review-selected"
private val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)

/**
 * The line selection, its copy, the context menus and the keys (F7, Alt+Down, Shortcut+C, Escape) of the diff list;
 * the list calls the event handlers and [paint].
 */
internal class DiffInteractions(
    private val rows: ObservableList<ReviewRow>,
    private val lines: ReviewLineSource,
    private val host: InteractionHost,
    private val actions: () -> LineActions?,
) {
    /** The current selection; `null` without. */
    var selection: LineSelection? = null
        private set

    private var selectedKeys: Set<RowKey> = emptySet()
    private var menu: ContextMenu? = null
    private var lastTarget: Pair<Int, RowKey>? = null

    private class Hit(
        val index: Int,
        val part: Int?,
    )

    private fun hit(target: Any?): Hit? {
        var child: Node? = null
        var node = target as? Node
        while (node != null && node !== host.owner) {
            val index = host.indexOfCell(node)
            if (index >= 0) {
                val part = child?.let { c -> (node as? Parent)?.childrenUnmodifiable?.indexOf(c) }
                return Hit(index, part)
            }
            child = node
            node = node.parent
        }
        return null
    }

    /** The half of a split row the column [part] belongs to; `null` in the unified view. */
    private fun sideOf(
        row: ReviewRow,
        part: Int,
    ): ReviewSide? =
        if (row is ReviewRow.SplitLine) {
            if (part < 2) {
                ReviewSide.ORIGINAL
            } else {
                ReviewSide.CURRENT
            }
        } else {
            null
        }

    private fun isNumber(
        row: ReviewRow,
        part: Int,
    ) = if (row is ReviewRow.SplitLine) part == 0 || part == 2 else part == 0 || part == 1

    private fun isLine(row: ReviewRow) = row is ReviewRow.UnifiedLine || row is ReviewRow.SplitLine

    fun onMousePressed(event: MouseEvent) {
        if (event.button != MouseButton.PRIMARY || event.isPopupTrigger) return
        val at = hit(event.target) ?: return
        val part = at.part ?: return
        val row = rows[at.index]
        if (!isLine(row) || !isNumber(row, part)) return
        val side = sideOf(row, part)
        val current = selection
        val next =
            if (event.isShiftDown && current != null) {
                current.extend(rows, at.index, side)
            } else {
                LineSelection.at(rows, at.index, side)
            }
        if (next != null) select(next)
    }

    fun onContextMenu(event: ContextMenuEvent) {
        if (event.isKeyboardTrigger) return keyboardMenu(event)
        val at = hit(event.target) ?: return
        val row = rows[at.index]
        menu?.hide()
        val items =
            if (row is ReviewRow.FileHeader) {
                headerItems(row)
            } else {
                val part = at.part ?: return
                if (!isLine(row)) return
                val side = sideOf(row, part)
                val selected = selection
                val keep =
                    selected != null &&
                        at.index in selected.rows(rows) &&
                        (side == null || side in selected.sidesOf(row))
                if (!keep) select(LineSelection.at(rows, at.index, side) ?: return)
                lineItems()
            }
        menu = ContextMenu(*items.toTypedArray()).also { it.show(host.owner, event.screenX, event.screenY) }
        event.consume()
    }

    /** Shift+F10 or the menu key on the focused list: the menu of the selected lines, at the first one shown. */
    private fun keyboardMenu(event: ContextMenuEvent) {
        val selected = selection?.rows(rows).orEmpty()
        if (selected.isEmpty()) return
        val anchor = selected.firstNotNullOfOrNull { host.cell(it) } ?: host.owner
        val at = anchor.localToScreen(anchor.layoutBounds.minX, anchor.layoutBounds.minY) ?: return
        menu?.hide()
        menu = ContextMenu(*lineItems().toTypedArray()).also { it.show(host.owner, at.x, at.y) }
        event.consume()
    }

    fun onKey(event: KeyEvent) {
        if (event.eventType != KeyEvent.KEY_PRESSED) return
        when {
            COPY.match(event) -> {
                if (selection != null) consume(event, ::copyLines)
            }

            event.code == KeyCode.ESCAPE && !event.isShortcutDown && !event.isAltDown -> {
                if (selection != null) consume(event) { select(null) }
            }

            event.code == KeyCode.F7 && !event.isShortcutDown && !event.isAltDown -> {
                consume(event) { jump(event.isShiftDown, ::nextChange) }
            }

            event.isAltDown && !event.isShortcutDown && !event.isShiftDown &&
                (event.code == KeyCode.DOWN || event.code == KeyCode.UP) -> {
                consume(event) { jump(event.code == KeyCode.UP, ::nextFileHeader) }
            }
        }
    }

    private fun consume(
        event: KeyEvent,
        action: () -> Unit,
    ) {
        action()
        event.consume()
    }

    private fun jump(
        backwards: Boolean,
        target: (List<ReviewRow>, Int, Boolean) -> Int?,
    ) {
        val index = target(rows, currentRow(), !backwards) ?: return
        lastTarget = index to rowKey(rows[index])
        host.reveal(index)
    }

    /** Where a jump starts: the row it went to last while that is shown, else the selection's first row, else the top. */
    private fun currentRow(): Int {
        lastTarget?.let { (index, key) ->
            val valid = index in rows.indices && rowKey(rows[index]) == key
            if (valid && (host.cell(index) != null || host.firstVisibleRow() == index)) return index
        }
        val selected = selection?.rows(rows).orEmpty()
        return if (selected.any { host.cell(it) != null }) selected.first() else host.viewportRow()
    }

    fun clearSelection() = select(null)

    private fun select(next: LineSelection?) {
        selection = next
        lastTarget = null
        refresh()
    }

    /** Looks the selection up again after the rows changed: gone with its rows. */
    fun rowsChanged() = refresh()

    private fun refresh() {
        if (selection == null && selectedKeys.isEmpty()) return
        val indices = selection?.rows(rows).orEmpty()
        if (indices.isEmpty()) selection = null
        selectedKeys = indices.mapTo(HashSet()) { rowKey(rows[it]) }
        for (i in host.visibleIndices()) host.cell(i)?.let { paint(rows[i], it) }
    }

    /** Marks the selected number columns and text of [row]'s [cell]. */
    fun paint(
        row: ReviewRow,
        cell: Node,
    ) {
        val pane = cell as? LineRowPane ?: return
        val selected = rowKey(row) in selectedKeys
        val halves = if (row is ReviewRow.SplitLine) selection?.sidesOf(row).orEmpty() else emptyList()
        pane.childrenUnmodifiable.forEachIndexed { i, child ->
            val on =
                selected &&
                    (row !is ReviewRow.SplitLine || (if (i < 2) ReviewSide.ORIGINAL else ReviewSide.CURRENT) in halves)
            if (on) {
                if (SELECTED !in child.styleClass) child.styleClass += SELECTED
            } else {
                child.styleClass -= SELECTED
            }
        }
    }

    private fun copy(text: String) =
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })

    private fun copyLines() {
        selection?.let { copy(it.text(rows, lines)) }
    }

    private fun lineItems(): List<MenuItem> {
        val selected = selection ?: return emptyList()
        val first = selected.rows(rows).firstOrNull() ?: return emptyList()
        return listOf(
            MenuItem("Copy Lines").apply {
                accelerator = COPY
                setOnAction { copyLines() }
            },
            openItem(selected.path, nearestCurrentLine(rows, first)),
            MenuItem("Copy Path").apply { setOnAction { copy(selected.path) } },
        )
    }

    private fun headerItems(header: ReviewRow.FileHeader): List<MenuItem> {
        val path = header.path
        return listOf(
            openItem(path, null),
            MenuItem("Show History").apply {
                setOnAction { actions()?.let { it.showHistory(it.root.resolve(path)) } }
            },
            MenuItem("Copy Path").apply { setOnAction { copy(path) } },
            SeparatorMenuItem(),
            MenuItem(if (header.viewed) "Mark as Not Viewed" else "Mark as Viewed").apply {
                setOnAction { host.setViewed(path, !header.viewed) }
            },
        )
    }

    private fun openItem(
        path: String,
        line: Int?,
    ): MenuItem {
        val lineActions = actions()
        val exists = lineActions != null && Files.isRegularFile(lineActions.root.resolve(path))
        return MenuItem(if (exists) "Open in Editor" else "Open in Editor (not in the working tree)").apply {
            isDisable = !exists
            setOnAction { lineActions?.let { it.openFile(it.root.resolve(path), line) } }
        }
    }
}
