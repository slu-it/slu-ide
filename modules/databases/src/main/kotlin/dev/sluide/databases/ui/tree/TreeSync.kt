package dev.sluide.databases.ui.tree

import javafx.scene.control.TreeItem
import java.util.IdentityHashMap

/** One node to show: [key] is its identity across reloads, [expanded] only applies when its item is new. */
internal class SyncNode<K, V>(
    val key: K,
    val value: V,
    val children: List<SyncNode<K, V>> = emptyList(),
    val expanded: Boolean = false,
)

/**
 * Keeps the [TreeItem]s below [root] in step with the [SyncNode]s of each reload: an item whose key is still there
 * stays the same object (so it keeps its expansion, and the tree view its selection), only its value changes; new
 * keys get new items, gone keys lose theirs. A child list that did not change is not touched. Works without the
 * toolkit.
 */
internal class TreeSync<K : Any, V : Any>(
    val root: TreeItem<V> = TreeItem(),
) {
    private var items = HashMap<K, TreeItem<V>>()
    private var keys = IdentityHashMap<TreeItem<V>, K>()

    /** Makes the items below [root] show [nodes]. */
    fun update(nodes: List<SyncNode<K, V>>) {
        val previousKeys = keys
        items = HashMap()
        keys = IdentityHashMap()
        sync(root, nodes, previousKeys)
    }

    /** The item of [key] since the last [update], `null` if it has none. */
    fun itemOf(key: K): TreeItem<V>? = items[key]

    private fun sync(
        parent: TreeItem<V>,
        nodes: List<SyncNode<K, V>>,
        previousKeys: Map<TreeItem<V>, K>,
    ) {
        val existing = parent.children.mapNotNull { child -> previousKeys[child]?.let { it to child } }.toMap()
        val wanted =
            nodes.map { node ->
                val item = existing[node.key] ?: TreeItem(node.value).apply { isExpanded = node.expanded }
                if (item.value != node.value) item.value = node.value
                items[node.key] = item
                keys[item] = node.key
                sync(item, node.children, previousKeys)
                item
            }
        if (!sameItems(parent.children, wanted)) parent.children.setAll(wanted)
    }

    private fun sameItems(
        current: List<TreeItem<V>>,
        wanted: List<TreeItem<V>>,
    ) = current.size == wanted.size && current.indices.all { current[it] === wanted[it] }
}
