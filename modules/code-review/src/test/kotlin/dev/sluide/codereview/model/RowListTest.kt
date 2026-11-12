package dev.sluide.codereview.model

import javafx.collections.ListChangeListener
import kotlin.test.Test
import kotlin.test.assertEquals

class RowListTest {
    private fun rows(vararg paths: String): List<ReviewRow> = paths.map { ReviewRow.Spacer(it) }

    private fun RowList.changesOf(action: RowList.() -> Unit): List<String> {
        val changes = mutableListOf<String>()
        addListener(
            ListChangeListener<ReviewRow> { change ->
                while (change.next()) changes += "${change.from} ${change.removedSize} ${change.addedSize}"
            },
        )
        action()
        return changes
    }

    private fun list(vararg paths: String) = RowList().apply { replaceAll(rows(*paths)) }

    @Test
    fun `only the middle that differs is reported`() {
        val list = list("a", "b", "c", "d", "e")

        val changes = list.changesOf { replace(0, 5, rows("a", "b", "x", "y", "z", "d", "e")) }

        assertEquals(listOf("2 1 3"), changes)
        assertEquals(listOf("a", "b", "x", "y", "z", "d", "e"), list.map { it.path })
    }

    @Test
    fun `an identical replacement reports nothing`() {
        val list = list("a", "b")

        assertEquals(emptyList(), list.changesOf { replace(0, 2, rows("a", "b")) })
    }

    @Test
    fun `a pure insertion and a pure removal are reported as such`() {
        val list = list("a", "d")

        assertEquals(listOf("1 0 2"), list.changesOf { replace(0, 2, rows("a", "b", "c", "d")) })
        assertEquals(listOf("1 2 0"), list.changesOf { replace(0, 4, rows("a", "d")) })
        assertEquals(listOf("a", "d"), list.map { it.path })
    }

    @Test
    fun `a range inside the list is replaced in place`() {
        val list = list("a", "b", "c", "d")

        val changes = list.changesOf { replace(1, 3, rows("b", "x")) }

        assertEquals(listOf("2 1 1"), changes)
        assertEquals(listOf("a", "b", "x", "d"), list.map { it.path })
    }
}
