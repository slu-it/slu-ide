package dev.sluide.gitlog

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import javafx.animation.Animation
import javafx.animation.PauseTransition
import javafx.collections.ListChangeListener
import javafx.geometry.Pos
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.util.Duration
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import org.kordamp.ikonli.materialdesign2.MaterialDesignT

/**
 * The branches pane left of the commits (ADR 0040): a search field above a tree of `HEAD`, the local branches, the
 * remotes with their branches and the tags ([branchNodes]) of the refs the log read last. Selecting exactly one branch
 * hands it to [navigate] (`null` for any other selection); a double-click, Enter or Filter Log by Branch hands the
 * full refnames of the selected branches to [filter]. Collapsed nodes (Tags at first) and the selection stay when the
 * refs are [shown][show] again.
 */
internal class BranchesPane : BorderPane() {
    /** Shows the commit of the one selected branch; `null` when not exactly one branch is selected. */
    var navigate: (GitRef?) -> Unit = {}

    /** Told when new refs moved the one selected branch: its ref before and after; the receiver decides what follows. */
    var moved: (GitRef, GitRef) -> Unit = { _, _ -> }

    /** Filters the log by the given full refnames (or `HEAD`). */
    var filter: (List<String>) -> Unit = {}

    internal val search =
        TextField().apply {
            styleClass += "git-branches-search-text"
            promptText = "Search branches"
            HBox.setHgrow(this, Priority.ALWAYS)
            setOnAction { focusFirstBranch() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                when (event.code) {
                    KeyCode.ESCAPE -> {
                        if (text.isNotEmpty()) {
                            clear()
                            event.consume()
                        }
                    }

                    KeyCode.DOWN -> {
                        focusFirstBranch()
                        event.consume()
                    }

                    else -> {}
                }
            }
        }

    /** The search field with a magnifier, as high as the filter bar next to it. */
    internal val bar =
        HBox(
            HBox(FontIcon(MaterialDesignM.MAGNIFY), search).apply {
                styleClass += "git-branches-search"
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(this, Priority.ALWAYS)
            },
        ).apply {
            styleClass += "git-branches-bar"
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }

    internal val placeholder = Label().apply { styleClass += "git-branches-placeholder" }

    internal val filterItem = MenuItem(FILTER_ONE).apply { setOnAction { filterSelected() } }
    internal val copyNameItem = MenuItem("Copy Name").apply { setOnAction { copyNames() } }

    internal val tree: TreeView<BranchNode> =
        TreeView<BranchNode>(TreeItem()).apply {
            styleClass += "git-branches-tree"
            isShowRoot = false
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            setCellFactory { BranchCell() }
            contextMenu = ContextMenu(filterItem, SeparatorMenuItem(), copyNameItem)
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.ENTER && !event.isShortcutDown && enter()) event.consume()
            }
        }

    /** The refs shown; `null` before they are read, or if they could not be. */
    private var refs: GitRefs? = null

    /** Whether refs were [shown][show] since the last [reset]. */
    private var loaded = false

    /** What the placeholder says while nothing is loaded: nothing, or "Loading…". */
    private var waiting = ""

    /** The whole tree of [refs], unfiltered. */
    private var nodes: List<BranchNode> = emptyList()

    /** The paths of the collapsed nodes, kept while searching (when everything is expanded). */
    private val collapsed = mutableSetOf(TAGS)

    /** Set while the tree is rebuilt, whose selection changes then are not the user's. */
    private var rebuilding = false

    private val searching get() = search.text.isNotBlank()

    /** Applies a typed search text after a pause: with thousands of tags, not on every keystroke. */
    internal val debounce = PauseTransition(SEARCH_DELAY).apply { setOnFinished { applySearch() } }

    init {
        styleClass += "git-branches"
        minWidth = 0.0
        top = bar
        center = StackPane(tree, placeholder)
        search.textProperty().subscribe {
            _,
            text,
            ->
            if (text.isNullOrBlank()) applySearch() else debounce.playFromStart()
        }
        tree.selectionModel.selectedItems.addListener(
            ListChangeListener {
                updateActions()
                if (!rebuilding) navigate(selectedRef)
            },
        )
        updateActions()
        updatePlaceholder(emptyList())
    }

    /** The ref of the one selected branch; `null` for none, a group or folder, or several. */
    val selectedRef: GitRef?
        get() =
            tree.selectionModel.selectedItems
                .singleOrNull()
                ?.let { it.value as? BranchLeaf }
                ?.ref

    /** The selected branches in tree order. */
    internal val selectedBranches: List<BranchLeaf>
        get() =
            tree.selectionModel.selectedIndices
                .sorted()
                .mapNotNull { tree.getTreeItem(it)?.value as? BranchLeaf }

    /** Says "Loading…" while no refs are shown. */
    fun loading() {
        waiting = LOADING
        if (!loaded) updatePlaceholder(emptyList())
    }

    /** Shows the tree of [refs] (`null`: they could not be read), keeping the selection and collapsed nodes. */
    fun show(refs: GitRefs?) {
        if (loaded && refs == this.refs) return
        loaded = true
        this.refs = refs
        nodes = refs?.let(::branchNodes).orEmpty()
        rebuild(top = null)
    }

    /** Shows nothing, without search text and selection, Tags collapsed again; for another repository. */
    fun reset() {
        debounce.stop()
        loaded = false
        waiting = ""
        refs = null
        nodes = emptyList()
        collapsed.clear()
        collapsed += TAGS
        rebuilding = true
        try {
            tree.selectionModel.clearSelection()
            tree.root.children.clear()
        } finally {
            rebuilding = false
        }
        search.clear()
        updatePlaceholder(emptyList())
    }

    /** Focuses the search field and selects its text. */
    fun focusSearch() {
        search.requestFocus()
        search.selectAll()
    }

    /** Filters the tree by the search text now. */
    private fun applySearch() {
        debounce.stop()
        rebuild(top = 0)
    }

    /**
     * Fills the tree with the [nodes] that pass the search, everything expanded while searching, and selects the
     * branches that were selected again; scrolls to the row [top], or (`null`) where it was.
     */
    private fun rebuild(top: Int?) {
        val shown = filterBranches(nodes, search.text)
        val before = selectedRef
        val keep = selectedBranches.map { it.ref.name }
        val scrollTo = top ?: firstVisibleRow()
        rebuilding = true
        try {
            val leaves = HashMap<String, TreeItem<BranchNode>>()
            tree.root.children.setAll(shown.map { item(it, leaves) })
            tree.selectionModel.clearSelection()
            // Not selected if it is in a collapsed node.
            keep
                .mapNotNull(leaves::get)
                .map(tree::getRow)
                .filter { it >= 0 }
                .forEach(tree.selectionModel::select)
        } finally {
            rebuilding = false
        }
        // The one selected branch is gone (e.g. no longer passes the search), or new refs moved it.
        val after = selectedRef
        if (before != null && after == null) navigate(null)
        if (before != null && after != null && after.commit != before.commit) moved(before, after)
        if (scrollTo != null && scrollTo >= 0) tree.scrollTo(scrollTo)
        updatePlaceholder(shown)
    }

    /** The tree item of [node] with everything below it; the items of leaves go into [leaves] by refname. */
    private fun item(
        node: BranchNode,
        leaves: MutableMap<String, TreeItem<BranchNode>>,
    ): TreeItem<BranchNode> =
        TreeItem(node).apply {
            when (node) {
                is BranchParent -> {
                    children.setAll(node.children.map { item(it, leaves) })
                    isExpanded = searching || node.path !in collapsed
                    expandedProperty().subscribe { _, expanded ->
                        if (!searching) {
                            if (expanded) collapsed -= node.path else collapsed += node.path
                        }
                    }
                }

                is BranchLeaf -> {
                    leaves[node.ref.name] = this
                }
            }
        }

    private fun firstVisibleRow(): Int? = (tree.lookup(".virtual-flow") as? VirtualFlow<*>)?.firstVisibleCell?.index

    private fun updatePlaceholder(shown: List<BranchNode>) {
        val text =
            when {
                !loaded -> waiting
                refs == null -> "The branches could not be read."
                nodes.isEmpty() -> "No branches yet."
                shown.isEmpty() -> "No matching branches"
                else -> null
            }
        placeholder.text = text.orEmpty()
        placeholder.isVisible = text != null
        tree.isVisible = text == null
    }

    /** Selects the first branch shown and moves the focus to the tree. */
    private fun focusFirstBranch() {
        if (debounce.status == Animation.Status.RUNNING) applySearch()
        tree.requestFocus()
        val first =
            (0 until tree.expandedItemCount).firstOrNull { tree.getTreeItem(it)?.value is BranchLeaf } ?: return
        tree.selectionModel.clearAndSelect(first)
        tree.focusModel.focus(first)
        tree.scrollTo(first)
    }

    /** Enter: a single selected group or folder opens or closes; selected branches filter the log. */
    private fun enter(): Boolean {
        val selected = tree.selectionModel.selectedItems
        val single = selected.singleOrNull()
        if (single != null && single.value is BranchParent) {
            single.isExpanded = !single.isExpanded
            return true
        }
        return filterSelected()
    }

    private fun filterSelected(): Boolean {
        val names = selectedBranches.map { it.ref.name }
        if (names.isEmpty()) return false
        filter(names)
        return true
    }

    private fun copyNames() {
        val names = selectedBranches.map { it.ref.shortName }
        if (names.isEmpty()) return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(names.joinToString("\n")) })
    }

    private fun updateActions() {
        val count = selectedBranches.size
        filterItem.text = if (count > 1) "Filter Log by $count Branches" else FILTER_ONE
        filterItem.isDisable = count == 0
        copyNameItem.isDisable = count == 0
    }

    /** A group (its name), a remote or folder (a folder icon) or a branch (its ref icon, the current one in bold). */
    private inner class BranchCell : TreeCell<BranchNode>() {
        private val icon = FontIcon()
        private val name = Label()
        private val box = HBox(icon, name).apply { alignment = Pos.CENTER_LEFT }

        init {
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && item is BranchLeaf) {
                    filterSelected()
                    event.consume()
                }
            }
        }

        override fun updateItem(
            item: BranchNode?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            tooltip = null
            if (empty || item == null) {
                graphic = null
                return
            }
            name.text = item.name
            name.styleClass.setAll("label", "git-branch-name")
            when (item) {
                is BranchGroup -> {
                    icon.isVisible = false
                    icon.isManaged = false
                }

                is BranchRemote, is BranchFolder -> {
                    showIcon(MaterialDesignF.FOLDER_OUTLINE, "git-branch-folder-icon")
                }

                is BranchLeaf -> {
                    val kind = if (item.current) GitRefKind.HEAD else item.ref.kind
                    showIcon(MaterialDesignT.TAG_OUTLINE, "git-ref-icon", refStyleClass(kind))
                    if (item.current &&
                        item.ref.kind == GitRefKind.LOCAL_BRANCH
                    ) {
                        name.styleClass += "git-branch-current"
                    }
                    tooltip = Tooltip(item.ref.name)
                }
            }
            graphic = box
        }

        private fun showIcon(
            code: Ikon,
            vararg classes: String,
        ) {
            icon.iconCode = code
            icon.styleClass.setAll("ikonli-font-icon", *classes)
            icon.isVisible = true
            icon.isManaged = true
        }
    }

    private companion object {
        const val FILTER_ONE = "Filter Log by Branch"
        const val LOADING = "Loading…"
        val SEARCH_DELAY: Duration = Duration.millis(200.0)
    }
}
