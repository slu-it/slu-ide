package dev.sluide.docker.tab.tree

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
 * stays the same object (so it keeps its expansion), only its value changes (when [isSame] says it differs); new keys
 * get new items, gone keys lose theirs. A child list is edited in place with removes and inserts, never replaced as a
 * whole, so the tree view keeps its selection and a list that did not change fires nothing. Works without the toolkit.
 *
 * Copy of `dev.sluide.databases.ui.tree.TreeSync`; keep the two in sync.
 */
internal class TreeSync<K : Any, V : Any>(
    val root: TreeItem<V> = TreeItem(),
    private val isSame: (V, V) -> Boolean = { a, b -> a == b },
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

    /** The key of [item] since the last [update], `null` if it is not one of ours. */
    fun keyOf(item: TreeItem<V>): K? = keys[item]

    private fun sync(
        parent: TreeItem<V>,
        nodes: List<SyncNode<K, V>>,
        previousKeys: Map<TreeItem<V>, K>,
    ) {
        val existing = parent.children.mapNotNull { child -> previousKeys[child]?.let { it to child } }.toMap()
        val wanted =
            nodes.map { node ->
                val item = existing[node.key] ?: TreeItem(node.value).apply { isExpanded = node.expanded }
                if (!isSame(item.value, node.value)) item.value = node.value
                items[node.key] = item
                keys[item] = node.key
                sync(item, node.children, previousKeys)
                item
            }
        edit(parent.children, wanted)
    }

    private fun edit(
        current: MutableList<TreeItem<V>>,
        wanted: List<TreeItem<V>>,
    ) {
        if (current.size == wanted.size && current.indices.all { current[it] === wanted[it] }) return
        val keep = IdentityHashMap<TreeItem<V>, Boolean>().apply { wanted.forEach { put(it, true) } }
        val gone = current.filter { it !in keep }
        if (gone.isNotEmpty()) current.removeAll(gone.toSet())
        wanted.forEachIndexed { index, item ->
            if (index < current.size && current[index] === item) return@forEachIndexed
            val at = current.indexOf(item)
            if (at >= 0) current.removeAt(at)
            current.add(index, item)
        }
    }
}
