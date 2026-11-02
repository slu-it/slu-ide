package dev.sluide.databases.ui.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TreeSyncTest {
    private val sync = TreeSync<String, String>()

    private fun node(
        key: String,
        value: String = key,
        expanded: Boolean = false,
        vararg children: SyncNode<String, String>,
    ) = SyncNode(key, value, children.toList(), expanded)

    private fun values() = sync.root.children.map { it.value }

    @Test
    fun `an item whose key stays is the same object with the new value and keeps its expansion`() {
        sync.update(listOf(node("a", "A", false, node("a1"))))
        val a = checkNotNull(sync.itemOf("a"))
        a.isExpanded = true
        sync.update(listOf(node("a", "A2", false, node("a1"), node("a2"))))
        assertSame(a, sync.itemOf("a"))
        assertEquals("A2", a.value)
        assertTrue(a.isExpanded)
        assertEquals(listOf("a1", "a2"), a.children.map { it.value })
    }

    @Test
    fun `new keys get items, expanded only if the node says so, and gone keys lose theirs`() {
        sync.update(listOf(node("a"), node("b", expanded = true)))
        assertTrue(checkNotNull(sync.itemOf("b")).isExpanded)
        assertEquals(false, checkNotNull(sync.itemOf("a")).isExpanded)
        sync.update(listOf(node("b"), node("c")))
        assertEquals(listOf("b", "c"), values())
        assertNull(sync.itemOf("a"))
    }

    @Test
    fun `an unchanged reload does not touch the child lists`() {
        sync.update(listOf(node("a"), node("b")))
        var changes = 0
        sync.root.children.addListener(javafx.collections.ListChangeListener { changes++ })
        sync.update(listOf(node("a"), node("b")))
        assertEquals(0, changes)
    }

    @Test
    fun `an item moves with its key when the order changes`() {
        sync.update(listOf(node("a"), node("b")))
        val b = checkNotNull(sync.itemOf("b"))
        sync.update(listOf(node("b"), node("a")))
        assertSame(b, sync.root.children.first())
    }
}
