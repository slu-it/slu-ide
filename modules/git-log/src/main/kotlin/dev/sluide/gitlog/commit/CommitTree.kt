package dev.sluide.gitlog.commit

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.gitlog.data.ChangeFile
import dev.sluide.gitlog.data.ChangeFolder
import dev.sluide.gitlog.data.ChangeNode
import dev.sluide.gitlog.data.changeTree
import dev.sluide.gitlog.ui.ChangeGraphic
import javafx.beans.InvalidationListener
import javafx.beans.WeakInvalidationListener
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox

/** The two groups of the Commit tab's tree (ADR 0048). */
internal enum class CommitGroup(
    val title: String,
) {
    CHANGES("Changes"),
    UNVERSIONED("Unversioned Files"),
}

/** A node of the Commit tab's tree; [paths] are the files below it (or the file itself) as the checks know them. */
internal sealed interface CommitNode {
    val group: CommitGroup
    val paths: List<String>
}

/** The root of a [group], with the count of its files. */
internal data class CommitGroupNode(
    override val group: CommitGroup,
    override val paths: List<String>,
) : CommitNode

/** A folder or file ([node]) of a [group]; a file's single path is its path as given to git. */
internal data class CommitEntryNode(
    val node: ChangeNode,
    override val group: CommitGroup,
    override val paths: List<String>,
) : CommitNode

/**
 * The items of the Commit tab's tree for the tracked [files] and the [untracked] paths: "Changes" and "Unversioned
 * Files", each left out while empty, with their folders and files ([changeTree]); the nodes whose ids
 * ([commitNodeId]) are [collapsed] are collapsed. A nested repository, listed as its folder with a trailing slash, is
 * shown by its name.
 */
internal fun commitTreeItems(
    files: List<GitChangedFile>,
    untracked: List<String>,
    collapsed: Set<String>,
): List<TreeItem<CommitNode>> {
    val untrackedFiles = untracked.map { GitChangedFile(GitChange.ADDED, it.removeSuffix("/")) }
    val untrackedPaths = untrackedFiles.zip(untracked).associate { (file, path) -> file.path to path }
    return listOfNotNull(
        groupItem(CommitGroup.CHANGES, files, { it.path }, collapsed).takeIf { files.isNotEmpty() },
        groupItem(CommitGroup.UNVERSIONED, untrackedFiles, { untrackedPaths.getValue(it.path) }, collapsed)
            .takeIf { untracked.isNotEmpty() },
    )
}

private fun groupItem(
    group: CommitGroup,
    files: List<GitChangedFile>,
    pathOf: (GitChangedFile) -> String,
    collapsed: Set<String>,
): TreeItem<CommitNode> =
    TreeItem<CommitNode>(CommitGroupNode(group, files.map(pathOf))).apply {
        children.setAll(changeTree(files).map { entryItem(it, group, pathOf, collapsed) })
        isExpanded = commitNodeId(value) !in collapsed
    }

private fun entryItem(
    node: ChangeNode,
    group: CommitGroup,
    pathOf: (GitChangedFile) -> String,
    collapsed: Set<String>,
): TreeItem<CommitNode> =
    when (node) {
        is ChangeFolder -> {
            TreeItem<CommitNode>(CommitEntryNode(node, group, filesBelow(node).map(pathOf))).apply {
                children.setAll(node.children.map { entryItem(it, group, pathOf, collapsed) })
                isExpanded = commitNodeId(value) !in collapsed
            }
        }

        is ChangeFile -> {
            TreeItem(CommitEntryNode(node, group, listOf(pathOf(node.file))))
        }
    }

private fun filesBelow(node: ChangeNode): List<GitChangedFile> =
    when (node) {
        is ChangeFolder -> node.children.flatMap(::filesBelow)
        is ChangeFile -> listOf(node.file)
    }

/** Names a node across rebuilds: its group and, for a folder or file, its path. */
internal fun commitNodeId(node: CommitNode): String =
    when (node) {
        is CommitGroupNode -> {
            node.group.name
        }

        is CommitEntryNode -> {
            when (val entry = node.node) {
                is ChangeFolder -> "${node.group.name}/${entry.path}/"
                is ChangeFile -> "${node.group.name}/${entry.file.path}"
            }
        }
    }

/** The ids ([commitNodeId]) of the collapsed nodes below [item] that have children. */
internal fun collapsedCommitIds(item: TreeItem<CommitNode>): Set<String> =
    item.children
        .flatMap { child ->
            val own = child.value?.takeIf { !child.isExpanded && child.children.isNotEmpty() }?.let(::commitNodeId)
            listOfNotNull(own) + collapsedCommitIds(child)
        }.toSet()

/** The items below [item], in tree order. */
internal fun commitRows(item: TreeItem<CommitNode>): List<TreeItem<CommitNode>> =
    item.children.flatMap { listOf(it) + commitRows(it) }

/**
 * A node of the Commit tab with its checkbox: a group's title and count, or a folder or file as [ChangeGraphic] shows
 * it, an untracked file's name in the colour of unversioned files. The checkbox shows [stateOf] the node and hands it
 * to [toggle]; a double-click on a file asks for its diff ([showDiff]).
 */
internal class CommitCell(
    private val stateOf: (CommitNode) -> CheckState,
    private val toggle: (CommitNode) -> Unit,
    private val showDiff: () -> Unit,
) : TreeCell<CommitNode>() {
    private val check =
        CheckBox().apply {
            styleClass += "git-commit-check"
            isFocusTraversable = false
            setOnAction { item?.let(toggle) }
        }
    private val look = ChangeGraphic()
    private val groupName = Label().apply { styleClass += "git-commit-group" }
    private val groupCount = Label().apply { styleClass += "git-commit-count" }
    private val groupBox = HBox(groupName, groupCount).apply { alignment = Pos.CENTER_LEFT }
    private val box = HBox(check).apply { alignment = Pos.CENTER_LEFT }

    private val iconUpdater = InvalidationListener { updateIcon() }
    private val weakIconUpdater = WeakInvalidationListener(iconUpdater)

    init {
        // A folder opens and closes without a call to updateItem.
        treeItemProperty().addListener { _, old, new ->
            old?.expandedProperty()?.removeListener(weakIconUpdater)
            new?.expandedProperty()?.addListener(weakIconUpdater)
        }
        setOnMouseClicked { event ->
            val file = (item as? CommitEntryNode)?.node is ChangeFile
            if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && file) {
                showDiff()
                event.consume()
            }
        }
    }

    override fun updateItem(
        item: CommitNode?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        text = null
        if (empty || item == null) {
            graphic = null
            tooltip = null
            return
        }
        val state = stateOf(item)
        check.isSelected = state == CheckState.ALL
        check.isIndeterminate = state == CheckState.SOME
        when (item) {
            is CommitGroupNode -> {
                groupName.text = item.group.title
                groupCount.text = item.paths.size.toString()
                tooltip = null
                box.children.setAll(check, groupBox)
            }

            is CommitEntryNode -> {
                val nameClass = if (item.group == CommitGroup.UNVERSIONED) UNVERSIONED_CLASS else null
                tooltip = look.show(item.node, treeItem?.isExpanded == true, nameClass)?.let(::Tooltip)
                box.children.setAll(check, look.box)
            }
        }
        graphic = box
    }

    private fun updateIcon() {
        val entry = item as? CommitEntryNode ?: return
        val treeItem = treeItem
        if (isEmpty || treeItem == null) return
        look.showIcon(entry.node, treeItem.isExpanded)
    }

    private companion object {
        const val UNVERSIONED_CLASS = "git-change-unversioned"
    }
}
