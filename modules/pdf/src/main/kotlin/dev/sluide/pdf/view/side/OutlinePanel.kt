package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.OutlineItem
import dev.sluide.pdf.view.PdfFileView
import dev.sluide.pdf.view.PdfInfo
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import java.util.IdentityHashMap

/**
 * The Outline tab of the side panel (ADR 0192): the outline of the view's document as a tree, its items expanded as
 * the PDF marks them, or a placeholder when it has none. A click on an item, or Enter on the selected one, scrolls the
 * pages to its target and gives them the focus; an item without a target is dimmed and does nothing. The selection
 * follows the current page ([OutlineFollow]) without scrolling the pages, except that a picked item stays selected
 * until the user scrolls or the pages move away from the page the jump left them on. FX thread only.
 */
internal class OutlinePanel(
    private val view: PdfFileView,
) {
    private val tree = TreeView(TreeItem<OutlineItem>())
    private val placeholder = Label(NO_OUTLINE).apply { styleClass += "pdf-outline-placeholder" }
    val node = StackPane(tree, placeholder)

    private var follow = OutlineFollow(emptyList())
    private var treeItems = IdentityHashMap<OutlineItem, TreeItem<OutlineItem>>()

    init {
        tree.isShowRoot = false
        tree.styleClass += "pdf-outline"
        tree.setCellFactory { OutlineCell() }
        tree.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 1) clickedItem(event)?.let(::jump)
        }
        tree.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                tree.selectionModel.selectedItem?.let(::jump)
                event.consume()
            }
        }
        view.info.subscribe(::show)
        view.currentPage.subscribe { page -> follow(page.toInt()) }
        // The page may stay the same (a picked item on the page of another one): follow it anyway.
        view.userScrolled += { follow(view.currentPage.get()) }
    }

    private fun show(info: PdfInfo?) {
        val outline = info?.outline.orEmpty()
        treeItems = IdentityHashMap()
        tree.root = TreeItem<OutlineItem>().also { root -> fill(root, outline) }
        follow = OutlineFollow(outline)
        tree.isVisible = outline.isNotEmpty()
        placeholder.isVisible = info != null && outline.isEmpty()
        follow(view.currentPage.get())
    }

    private fun fill(
        parent: TreeItem<OutlineItem>,
        items: List<OutlineItem>,
    ) {
        for (item in items) {
            val child = TreeItem(item)
            child.isExpanded = item.open
            treeItems[item] = child
            parent.children += child
            fill(child, item.children)
        }
    }

    /** Selects the item of [page] and scrolls the tree to it; never the pages. */
    private fun follow(page: Int) {
        val item = follow.itemFor(page) { treeItems[it]?.isExpanded == true }?.let(treeItems::get)
        if (item == null) {
            tree.selectionModel.clearSelection()
        } else {
            tree.selectionModel.select(item)
            showRow(tree, tree.selectionModel.selectedIndex)
        }
    }

    /** The item of the cell [event] hit, not the empty space below the items or a disclosure arrow. */
    private fun clickedItem(event: MouseEvent): TreeItem<OutlineItem>? {
        var hit = event.target as? Node
        while (hit != null && hit !== tree) {
            if (hit.styleClass.contains("tree-disclosure-node")) return null
            if (hit is TreeCell<*>) return if (hit.isEmpty) null else tree.getTreeItem(hit.index)
            hit = hit.parent
        }
        return null
    }

    private fun jump(item: TreeItem<OutlineItem>) {
        val target = item.value?.target
        if (target != null) view.scrollTo(target)
        // After the scroll, whose page change the selection followed: the pick stays until the next one.
        tree.selectionModel.select(item)
        if (target != null) view.pages.requestFocus()
    }

    private class OutlineCell : TreeCell<OutlineItem>() {
        init {
            // As wide as the tree, not as the title: a long title ends in an ellipsis instead of a horizontal scroll.
            prefWidth = 0.0
        }

        override fun updateItem(
            item: OutlineItem?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            val shown = item.takeUnless { empty }
            text = shown?.let { outlineTitle(it.title) }
            tooltip = shown?.let { Tooltip(it.title) }
            styleClass.remove(DIMMED)
            if (shown != null && shown.target == null) styleClass += DIMMED
        }
    }

    private companion object {
        const val NO_OUTLINE = "This PDF has no outline."
        const val DIMMED = "pdf-outline-dimmed"
    }
}
