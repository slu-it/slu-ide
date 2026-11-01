package dev.sluide.docker.tab.tree

import javafx.scene.control.TreeItem

/**
 * Keeps the [TreeItem]s below [root] in step with the [TreeNode]s of each reload: an item whose key is still there
 * stays the same object (so it keeps its expansion, and the tree view its selection), only its value changes; new
 * keys get new items, expanded if they are groups or Compose projects; items of gone keys are removed. A child list
 * that did not change is not touched, so a reload that changes nothing fires no change. Works without the toolkit.
 */
internal class TreeSync(
    val root: TreeItem<TreeRow> = TreeItem(),
) {
    private val items = HashMap<ResourceKey, TreeItem<TreeRow>>()

    /** Makes the items below [root] show [nodes]. */
    fun update(nodes: List<TreeNode>) {
        items.clear()
        sync(root, nodes)
    }

    /** The item of [key] since the last [update], `null` if it has none. */
    fun itemOf(key: ResourceKey): TreeItem<TreeRow>? = items[key]

    private fun sync(
        parent: TreeItem<TreeRow>,
        nodes: List<TreeNode>,
    ) {
        val existing = parent.children.associateBy { it.value.key }
        val wanted =
            nodes.map { node ->
                val item =
                    existing[node.row.key]
                        ?: TreeItem(node.row).apply { isExpanded = node.row is GroupRow || node.row is ProjectRow }
                if (item.value != node.row) item.value = node.row
                sync(item, node.children)
                items[node.row.key] = item
                item
            }
        if (!sameItems(parent.children, wanted)) parent.children.setAll(wanted)
    }

    private fun sameItems(
        current: List<TreeItem<TreeRow>>,
        wanted: List<TreeItem<TreeRow>>,
    ) = current.size == wanted.size && current.indices.all { current[it] === wanted[it] }
}
