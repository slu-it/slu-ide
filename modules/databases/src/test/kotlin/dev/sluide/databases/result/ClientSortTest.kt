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

    @Test
    fun `two nulls are equal and a null is after a value`() {
        val up = ClientSort.comparator(0, true)
        val down = ClientSort.comparator(0, false)
        val nothing = listOf<Any?>(null)
        val one = listOf<Any?>(1)

        assertEquals(0, up.compare(nothing, nothing))
        assertEquals(0, down.compare(nothing, nothing))
        assertEquals(1, up.compare(nothing, one))
        assertEquals(-1, up.compare(one, nothing))
        assertEquals(1, down.compare(nothing, one))
        assertEquals(-1, down.compare(one, nothing))
    }

    @Test
    fun `numbers compare by value, not by text`() {
        assertEquals(listOf(2, 9, 10, 100L), sorted(listOf(100L, 10, 2, 9)))
    }

    @Test
    fun `numbers beyond double precision still compare exactly`() {
        assertEquals(
            listOf(9007199254740992L, 9007199254740993L),
            sorted(listOf(9007199254740993L, 9007199254740992L)),
        )
        assertEquals(
            listOf(BigDecimal("1.00000000000000001"), BigDecimal("1.00000000000000002")),
            sorted(listOf(BigDecimal("1.00000000000000002"), BigDecimal("1.00000000000000001"))),
        )
        assertEquals(
            listOf(BigDecimal("0.1"), BigDecimal("0.10000000000000000001")),
            sorted(listOf(BigDecimal("0.10000000000000000001"), BigDecimal("0.1"))),
        )
        assertEquals(-1, ClientSort.compareNumbers(0.1, BigDecimal("0.10000000000000000001")))
        assertEquals(1, ClientSort.compareNumbers(java.math.BigInteger("12345678901234567890"), 1L))
        assertEquals(-1, ClientSort.compareNumbers(1.5f, 2.5f))
        assertEquals(-1, ClientSort.compareNumbers(1.1f, 1.10000001))
        assertEquals(1, ClientSort.compareNumbers(5.toShort(), 4.toByte()))
    }

    @Test
    fun `not finite floats and doubles sort around the finite ones`() {
        assertEquals(
            listOf(Float.NEGATIVE_INFINITY, -1.5f, 1, Float.POSITIVE_INFINITY, Float.NaN),
            sorted(listOf(Float.NaN, 1, Float.POSITIVE_INFINITY, -1.5f, Float.NEGATIVE_INFINITY)),
        )
        assertEquals(
            listOf(-1.5, 1, Double.POSITIVE_INFINITY),
            sorted(listOf(Double.POSITIVE_INFINITY, 1, -1.5)),
        )
        assertEquals(-1, ClientSort.compareNumbers(Double.NEGATIVE_INFINITY, 1))
        assertEquals(1, ClientSort.compareNumbers(1, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `dates beyond four digits compare by value and other temporal types by class name`() {
        val far = LocalDate.of(10000, 1, 1)
        val near = LocalDate.of(9999, 12, 31)
        assertEquals(listOf(near, far), sorted(listOf(far, near)))
        val date = LocalDate.of(2020, 1, 1)
        val stamp = java.time.LocalDateTime.of(2019, 1, 1, 0, 0)
        assertEquals(listOf(date, stamp), sorted(listOf(stamp, date)))
    }

    @Test
    fun `a temporal that is not comparable sorts by its text`() {
        class Stamp(
            private val text: String,
        ) : java.time.temporal.Temporal {
            override fun isSupported(unit: java.time.temporal.TemporalUnit) = false

            override fun with(adjuster: java.time.temporal.TemporalAdjuster): java.time.temporal.Temporal = this

            override fun with(
                field: java.time.temporal.TemporalField,
                newValue: Long,
            ): java.time.temporal.Temporal = this

            override fun plus(
                amountToAdd: Long,
                unit: java.time.temporal.TemporalUnit,
            ): java.time.temporal.Temporal = this

            override fun until(
                endExclusive: java.time.temporal.Temporal,
                unit: java.time.temporal.TemporalUnit,
            ) = 0L

            override fun isSupported(field: java.time.temporal.TemporalField) = false

            override fun getLong(field: java.time.temporal.TemporalField) = 0L

            override fun toString() = text
        }
        val b = Stamp("b")
        val a = Stamp("a")

        assertEquals(listOf(a, b), sorted(listOf(b, a)))
        assertEquals(listOf(b, a), sorted(listOf(a, b), ascending = false))
    }

    @Test
    fun `bytes sort by value, not by their identity text`() {
        val values = (0 until 12).map { byteArrayOf((11 - it).toByte()) }
        val out = sorted(values).map { (it as ByteArray)[0].toInt() }

        assertEquals((0 until 12).toList(), out)
    }

    @Test
    fun `descending order reverses every kind`() {
        assertEquals(listOf(true, false), sorted(listOf(false, true), ascending = false))
        assertEquals(listOf("b", "a"), sorted(listOf("a", "b"), ascending = false))
        assertEquals(listOf(10, 9, 2), sorted(listOf(9, 2, 10), ascending = false))
    }
}
