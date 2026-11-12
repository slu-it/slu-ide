package dev.sluide.codereview.window

import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.list.ReviewDiffView
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewTreeNode
import dev.sluide.codereview.model.reviewTree
import dev.sluide.codereview.model.treeFiles
import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileicons.fileIconFor
import dev.sluide.fileicons.show
import javafx.beans.InvalidationListener
import javafx.beans.WeakInvalidationListener
import javafx.geometry.Pos
import javafx.scene.control.IndexedCell
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignM

/**
 * The style class colouring the name of a file with [change]; a copy is a new file like an added one (as in the file
 * headers), a renamed one counts as modified.
 */
internal fun nameStyleClass(change: ReviewChange): String =
    when (change) {
        ReviewChange.ADDED, ReviewChange.COPIED -> "review-change-added"
        ReviewChange.DELETED -> "review-change-deleted"
        else -> "review-change-modified"
    }

/** The tooltip of a renamed or copied [file], `null` for the others. */
internal fun changeTooltip(file: ReviewFile): String? {
    val old = file.oldPath ?: return null
    return when (file.change) {
        ReviewChange.RENAMED -> "Renamed from $old"
        ReviewChange.COPIED -> "Copied from $old"
        else -> null
    }
}

/**
 * The row to scroll a tree to so that [row] shows, or `null` when it already does ([firstVisible]..[lastVisible]);
 * a few rows stay above it for context.
 */
internal fun treeScrollTarget(
    row: Int,
    firstVisible: Int,
    lastVisible: Int,
): Int? = if (row in firstVisible..lastVisible) null else (row - CONTEXT_ROWS).coerceAtLeast(0)

private const val CONTEXT_ROWS = 2
private const val TOLERANCE = 1.0

/**
 * The files pane (ADR 0168): the filter field over the "x / y viewed" counter and the tree of the changed files. The
 * filter is `document.filter`. Clicking or pressing Enter on a file scrolls the diff to its header; the tree
 * follows the diff's top file without ever scrolling the diff, because only the user's click or Enter does that.
 */
internal class FilesPane(
    private val document: ReviewDocument,
    private val diffView: ReviewDiffView,
) {
    internal val filter =
        TextField().apply {
            styleClass += "review-files-filter-text"
            promptText = "Filter files"
            HBox.setHgrow(this, Priority.ALWAYS)
            setOnAction { jumpToFirstMatch() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                when (event.code) {
                    KeyCode.ESCAPE -> {
                        if (text.isNotEmpty()) {
                            clear()
                            event.consume()
                        }
                    }

                    KeyCode.DOWN -> {
                        focusTree()
                        event.consume()
                    }

                    else -> {}
                }
            }
        }

    internal val viewed = Label().apply { styleClass += "review-files-viewed" }

    internal val tree =
        TreeView<ReviewTreeNode>(TreeItem()).apply {
            styleClass += "review-files-tree"
            isShowRoot = false
            setCellFactory { FileCell() }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.ENTER && !event.isShortcutDown && openSelected()) event.consume()
            }
        }

    val node =
        VBox(
            HBox(FontIcon(MaterialDesignM.MAGNIFY), filter).apply {
                styleClass += "review-files-filter"
                alignment = Pos.CENTER_LEFT
            },
            viewed,
            tree,
        ).apply {
            styleClass += "review-files"
            stylesheets += FILE_ICONS_CSS
            VBox.setVgrow(tree, Priority.ALWAYS)
        }

    private val fileItems = HashMap<String, TreeItem<ReviewTreeNode>>()

    /** The file the user last scrolled the diff to from the tree or the filter; see [pinShown]. */
    private var pinned: String? = null

    init {
        filter.textProperty().bindBidirectional(document.filter)
        document.filesVersion.subscribe { _ -> rebuild() }
        document.filter.subscribe { _ -> rebuild() }
        document.viewedCount.subscribe { _ ->
            updateCounter()
            tree.refresh()
        }
        diffView.topFile.subscribe { path ->
            if (path == null || pinShown()) return@subscribe
            pinned = null
            showFile(path)
        }
        rebuild()
    }

    /** Puts the cursor into the filter with its text selected. */
    fun focusFilter() {
        filter.requestFocus()
        filter.selectAll()
    }

    private fun updateCounter() {
        viewed.text = "${document.viewedCount.get()} / ${document.files.size} viewed"
    }

    /** Builds the tree again, for a new file list or filter; the folders the user closed stay closed. */
    private fun rebuild() {
        val selected =
            tree.selectionModel.selectedItem
                ?.value
                ?.path
        val collapsed = collapsedFolders(tree.root)
        fileItems.clear()
        tree.root.children.setAll(reviewTree(document.files, document.filter.get()).map { item(it, collapsed) })
        selected?.let(fileItems::get)?.let(tree.selectionModel::select)
        updateCounter()
        if (!pinShown()) diffView.topFile.value?.let(::showFile)
    }

    /** The paths of the closed folders below [item]. */
    private fun collapsedFolders(item: TreeItem<ReviewTreeNode>): Set<String> =
        item.children
            .flatMap { child ->
                val node = child.value
                if (node.file != null) {
                    emptyList()
                } else {
                    listOfNotNull(node.path.takeIf { !child.isExpanded }) + collapsedFolders(child)
                }
            }.toSet()

    private fun item(
        node: ReviewTreeNode,
        collapsed: Set<String>,
    ): TreeItem<ReviewTreeNode> =
        TreeItem(node).apply {
            isExpanded = node.file != null || node.path !in collapsed
            children.setAll(node.children.map { item(it, collapsed) })
            if (node.file != null) fileItems[node.path] = this
        }

    /**
     * Selects [path]'s node (the app's own selection: it never scrolls the diff), opens its folders and scrolls the
     * tree if the row is out of view.
     */
    private fun showFile(path: String) {
        val item = fileItems[path] ?: return
        generateSequence(item.parent) { it.parent }.forEach { it.isExpanded = true }
        tree.selectionModel.select(item)
        val row = tree.getRow(item)
        tree.applyCss()
        tree.layout()
        val visible = fullyVisibleRows()
        val target = if (visible == null) row else treeScrollTarget(row, visible.first, visible.last)
        if (target != null) tree.scrollTo(target.coerceAtLeast(0))
    }

    /**
     * Whether the file the user last went to from the tree or the filter ([pinned]) keeps the selection: a file too near
     * the end to reach the diff's top stays selected while the diff shows its header and its last row (it is scrolled to
     * its end); any other scroll lets the selection follow the diff's top file again.
     */
    private fun pinShown(): Boolean {
        val path = pinned ?: return false
        val header = document.headerRow(path)
        return header >= 0 &&
            diffView.rowCell(header) != null &&
            diffView.rowCell(document.rows.lastIndex) != null
    }

    /** The rows of the tree's flow that show completely; `null` while there are none laid out. */
    private fun fullyVisibleRows(): IntRange? {
        val flow = tree.lookup(".virtual-flow") as? VirtualFlow<*> ?: return null
        val first = flow.firstVisibleCell ?: return null
        val last = flow.lastVisibleCell ?: return null
        val area = flow.localToScene(flow.layoutBounds)

        fun shows(cell: IndexedCell<*>): Boolean {
            val bounds = cell.localToScene(cell.layoutBounds)
            return bounds.minY >= area.minY - TOLERANCE && bounds.maxY <= area.maxY + TOLERANCE
        }
        val from = if (shows(first)) first.index else first.index + 1
        val to = if (shows(last)) last.index else last.index - 1
        return if (from <= to) from..to else null
    }

    private fun scrollDiffTo(path: String) {
        pinned = path
        val header = document.headerRow(path)
        if (header >= 0) diffView.scrollToRow(header, 0.0)
    }

    /** Enter: the selected file's diff, or a folder opens or closes. */
    private fun openSelected(): Boolean {
        val item = tree.selectionModel.selectedItem ?: return false
        val file = item.value?.file
        if (file == null) {
            item.isExpanded = !item.isExpanded
        } else {
            scrollDiffTo(file.path)
        }
        return true
    }

    private fun jumpToFirstMatch() {
        val first = treeFiles(reviewTree(document.files, document.filter.get())).firstOrNull() ?: return
        scrollDiffTo(first.path)
        diffView.node.requestFocus()
    }

    private fun focusTree() {
        val first = treeFiles(reviewTree(document.files, document.filter.get())).firstOrNull() ?: return
        fileItems[first.path]?.let(tree.selectionModel::select)
        tree.requestFocus()
    }

    /** A folder or a file: the file-type icon, the name coloured by its change, a check mark when viewed. */
    private inner class FileCell : TreeCell<ReviewTreeNode>() {
        private val icon = FontIcon().apply { styleClass += "review-file-icon" }
        private val name = Label()
        private val check =
            FontIcon(MaterialDesignC.CHECK).apply {
                styleClass += "review-viewed-check"
                managedProperty().bind(visibleProperty())
            }
        private val box = HBox(icon, name, check).apply { alignment = Pos.CENTER_LEFT }

        private val iconUpdater = InvalidationListener { updateIcon() }
        private val weakIconUpdater = WeakInvalidationListener(iconUpdater)

        init {
            // A folder opens and closes without a call to updateItem.
            treeItemProperty().addListener { _, old, new ->
                old?.expandedProperty()?.removeListener(weakIconUpdater)
                new?.expandedProperty()?.addListener(weakIconUpdater)
            }
            setOnMouseClicked { event ->
                val file = item?.file
                if (event.button == MouseButton.PRIMARY && file != null) {
                    treeView.selectionModel.select(treeItem)
                    scrollDiffTo(file.path)
                }
            }
        }

        override fun updateItem(
            item: ReviewTreeNode?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            if (empty || item == null) {
                graphic = null
                tooltip = null
                return
            }
            val file = item.file
            if (file == null) {
                name.styleClass.setAll("label", "review-file-folder")
            } else {
                name.styleClass.setAll("label", "review-file-name", nameStyleClass(file.change))
            }
            name.text = item.name
            check.isVisible = file != null && document.isViewed(file.path)
            tooltip = file?.let(::changeTooltip)?.let(::Tooltip)
            updateIcon()
            graphic = box
        }

        private fun updateIcon() {
            val item = item ?: return
            icon.show(fileIconFor(item.name, item.file == null, treeItem?.isExpanded == true) { true })
        }
    }
}
