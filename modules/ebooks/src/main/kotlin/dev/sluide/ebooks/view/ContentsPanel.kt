package dev.sluide.ebooks.view

import dev.sluide.ebooks.TocItem
import javafx.scene.Node
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent

/**
 * The Contents side panel of the reader (ADR 0165): the book's table of contents as a tree, all expanded. A click on
 * an entry, or Enter on the selected one, calls [onJump] once with the entry; the arrow keys only move the
 * selection. [follow] moves the selection to the entry of a reading position without jumping. FX thread only.
 */
internal class ContentsPanel(
    private val onJump: (TocItem) -> Unit,
) {
    private val tree = TreeView<TocItem>()
    private var items: List<TocItem> = emptyList()

    val node: Node get() = tree

    init {
        tree.isShowRoot = false
        tree.isFocusTraversable = true
        tree.styleClass += "ebook-contents"
        tree.minWidth = MIN_WIDTH
        tree.setCellFactory {
            object : TreeCell<TocItem>() {
                override fun updateItem(
                    item: TocItem?,
                    empty: Boolean,
                ) {
                    super.updateItem(item, empty)
                    text = if (empty) null else item?.title
                }
            }
        }
        tree.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 1 && clickedEntry(event)) jumpToSelected()
        }
        tree.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                jumpToSelected()
                event.consume()
            }
        }
    }

    /** Whether [event] hit an entry's cell, not the empty space below the entries or a disclosure arrow. */
    private fun clickedEntry(event: MouseEvent): Boolean {
        var hit = event.target as? Node
        while (hit != null && hit !== tree) {
            if (hit.styleClass.contains("arrow") || hit.styleClass.contains("tree-disclosure-node")) return false
            if (hit is TreeCell<*>) return !hit.isEmpty
            hit = hit.parent
        }
        return false
    }

    private fun jumpToSelected() {
        tree.selectionModel.selectedItem
            ?.value
            ?.let(onJump)
    }

    fun show(entries: List<TocItem>) {
        items = entries
        tree.root = TreeItem<TocItem>().also { root -> fill(root, entries) }
    }

    private fun fill(
        parent: TreeItem<TocItem>,
        entries: List<TocItem>,
    ) {
        parent.isExpanded = true
        for (entry in entries) {
            parent.children += TreeItem(entry).also { fill(it, entry.children) }
        }
    }

    /**
     * Selects the entry for [position], preferring one of [chapter] ([contentsItemAt]), and scrolls it into view;
     * clears the selection before the first entry.
     */
    fun follow(
        position: Int,
        chapter: Int? = null,
    ) {
        val target = contentsItemAt(items, position, chapter)
        if (tree.selectionModel.selectedItem?.value === target) return
        val treeItem = target?.let { find(tree.root, it) }
        if (treeItem == null) {
            tree.selectionModel.clearSelection()
        } else {
            tree.selectionModel.select(treeItem)
            tree.scrollTo((tree.selectionModel.selectedIndex - 2).coerceAtLeast(0))
        }
    }

    /** The entry shown as selected, for tests. */
    val selected: TocItem? get() = tree.selectionModel.selectedItem?.value

    /** The entries in reading order, as shown. */
    val entries: List<TocItem> get() = items

    private fun find(
        from: TreeItem<TocItem>?,
        wanted: TocItem,
    ): TreeItem<TocItem>? {
        from ?: return null
        if (from.value === wanted) return from
        return from.children.firstNotNullOfOrNull { find(it, wanted) }
    }

    private companion object {
        const val MIN_WIDTH = 140.0
    }
}
