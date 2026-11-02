package dev.sluide.databases.result

import java.math.BigDecimal
import java.math.BigInteger
import java.time.temporal.Temporal

/** Sorting of the rows already fetched, by the value of one column. */
object ClientSort {
    /**
     * Compares rows by the value at column [index]. Nulls go last in both directions. Numbers compare numerically
     * across types, temporals within their type, booleans false before true, ByteArray unsigned lexicographic,
     * everything else by its text (case-insensitively, then case-sensitively). Values of different kinds are ordered
     * by kind: numbers, temporals, booleans, bytes, text.
     */
    fun comparator(
        index: Int,
        ascending: Boolean,
    ): Comparator<List<Any?>> =
        Comparator { a, b ->
            val x = a[index]
            val y = b[index]
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                ascending -> compareValues(x, y)
                else -> compareValues(y, x)
            }
        }

    /** Rows are ordered by category first, so that mixed columns still give a consistent order. */
    private fun rank(value: Any): Int =
        when (value) {
            is Number -> 0
            is Temporal -> 1
            is Boolean -> 2
            is ByteArray -> 3
            else -> 4
        }

    private fun compareValues(
        a: Any,
        b: Any,
    ): Int {
        val byRank = rank(a).compareTo(rank(b))
        if (byRank != 0) return byRank
        return when {
            a is Number && b is Number -> compareNumbers(a, b)
            a is Boolean && b is Boolean -> a.compareTo(b)
            a is ByteArray && b is ByteArray -> compareBytes(a, b)
            a is Temporal && b is Temporal -> compareTemporals(a, b)
            else -> compareText(a.toString(), b.toString())
        }
    }

    private fun compareText(
        a: String,
        b: String,
    ): Int = a.compareTo(b, ignoreCase = true).takeIf { it != 0 } ?: a.compareTo(b)

    private fun compareTemporals(
        a: Temporal,
        b: Temporal,
    ): Int {
        val byClass = a::class.java.name.compareTo(b::class.java.name)
        if (byClass != 0) return byClass
        @Suppress("UNCHECKED_CAST")
        return if (a is Comparable<*>) (a as Comparable<Any>).compareTo(b) else compareText(a.toString(), b.toString())
    }

    internal fun compareNumbers(
        a: Number,
        b: Number,
    ): Int {
        val x = toBigDecimal(a)
        val y = toBigDecimal(b)
        return if (x != null && y != null) x.compareTo(y) else a.toDouble().compareTo(b.toDouble())
    }

    private fun toBigDecimal(n: Number): BigDecimal? =
        when (n) {
            is BigDecimal -> n
            is Double -> if (n.isFinite()) BigDecimal(n.toString()) else null
            is Float -> if (n.isFinite()) BigDecimal(n.toString()) else null
            is BigInteger -> BigDecimal(n)
            else -> BigDecimal.valueOf(n.toLong())
        }

    private fun compareBytes(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
