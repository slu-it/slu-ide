package dev.sluide.databases.result

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ClientSortTest {
    private fun sorted(
        values: List<Any?>,
        ascending: Boolean = true,
    ): List<Any?> = values.map { listOf(it) }.sortedWith(ClientSort.comparator(0, ascending)).map { it[0] }

    @Test
    fun `nulls go last in both directions`() {
        assertEquals(listOf(1, 2, null), sorted(listOf(null, 2, 1)))
        assertEquals(listOf(2, 1, null), sorted(listOf(null, 1, 2), ascending = false))
    }

    @Test
    fun `numbers compare across types`() {
        assertEquals(listOf(1, 1.5, BigDecimal("2.5"), 3L), sorted(listOf(3L, BigDecimal("2.5"), 1, 1.5)))
        assertEquals(
            listOf(Double.NEGATIVE_INFINITY, 0, Double.NaN),
            sorted(listOf(Double.NaN, 0, Double.NEGATIVE_INFINITY)),
        )
    }

    @Test
    fun `strings ignore case first, then compare case-sensitively`() {
        assertEquals(listOf("a", "B", "c"), sorted(listOf("c", "B", "a")))
        assertEquals(listOf("A", "a"), sorted(listOf("a", "A")))
    }

    @Test
    fun `booleans temporals and uuids`() {
        assertEquals(listOf(false, true), sorted(listOf(true, false)))
        val d1 = LocalDate.of(2020, 1, 1)
        val d2 = LocalDate.of(2021, 1, 1)
        assertEquals(listOf(d1, d2), sorted(listOf(d2, d1)))
        val u1 = UUID(0, 1)
        val u2 = UUID(0, 2)
        assertEquals(listOf(u1, u2), sorted(listOf(u2, u1)))
    }

    @Test
    fun `bytes compare unsigned lexicographically`() {
        val out = sorted(listOf(byteArrayOf(-1), byteArrayOf(1, 2), byteArrayOf(1)))
        assertContentEquals(byteArrayOf(1), out[0] as ByteArray)
        assertContentEquals(byteArrayOf(1, 2), out[1] as ByteArray)
        assertContentEquals(byteArrayOf(-1), out[2] as ByteArray)
    }

    @Test
    fun `mixed kinds are ordered by kind`() {
        assertEquals(
            listOf(10, LocalDate.of(2020, 1, 1), true, "9"),
            sorted(listOf("9", true, LocalDate.of(2020, 1, 1), 10)),
        )
    }

    @Test
    fun `forty mixed values sort without breaking the comparator contract`() {
        val values =
            (0 until 40).map {
                when (it % 8) {
                    0 -> it
                    1 -> it.toString()
                    2 -> LocalDate.of(2000 + it, 1, 1)
                    3 -> java.time.LocalDateTime.of(2000 + it, 1, 1, 0, 0)
                    4 -> it % 2 == 0
                    5 -> byteArrayOf(it.toByte())
                    6 -> UUID(0, it.toLong())
                    else -> listOf(it)
                }
            }
        for (ascending in listOf(true, false)) {
            val out = sorted(values.reversed() + values, ascending)
            assertEquals(80, out.size)
            val ranks =
                out.map { v ->
                    if (v is Number) {
                        0
                    } else if (v is java.time.temporal.Temporal) {
                        1
                    } else if (v is Boolean) {
                        2
                    } else if (v is ByteArray) {
                        3
                    } else {
                        4
                    }
                }
            assertEquals(if (ascending) ranks.sorted() else ranks.sortedDescending(), ranks)
        }
    }

    @Test
    fun `sorts by the given column`() {
        val rows = listOf(listOf<Any?>("x", 2), listOf<Any?>("y", 1))
        assertEquals(listOf("y", "x"), rows.sortedWith(ClientSort.comparator(1, true)).map { it[0] })
    }
}
