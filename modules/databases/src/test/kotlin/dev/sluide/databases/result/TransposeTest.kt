package dev.sluide.databases.result

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TransposeTest {
    @Test
    fun `one row per column with the labels first`() {
        val (columns, rows) =
            Transpose.of(
                listOf(col("id"), col("n", label = "Name")),
                listOf(listOf(1, "a"), listOf(2, null)),
            )
        assertEquals(listOf("Column", "1", "2"), columns.map { it.label })
        assertEquals(listOf(listOf("id", 1, 2), listOf("Name", "a", null)), rows)
    }

    @Test
    fun `no rows leaves only the labels`() {
        val (columns, rows) = Transpose.of(listOf(col("id")), emptyList())
        assertEquals(1, columns.size)
        assertEquals(listOf(listOf<Any?>("id")), rows)
    }
}
