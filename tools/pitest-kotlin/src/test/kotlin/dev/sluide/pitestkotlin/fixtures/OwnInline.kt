package dev.sluide.pitestkotlin.fixtures

class OwnInline {
    fun small(): Int = bounded(3) { it * 2 }

    fun large(limit: Int): Int = bounded(limit) { it + 1 }

    private inline fun bounded(
        limit: Int,
        step: (Int) -> Int,
    ): Int {
        var total = 0
        for (index in 0 until limit) {
            if (total > 100) break
            total += step(index)
        }
        return total
    }

    fun startsWithText(items: List<Any>): Boolean = firstIs<String>(items)

    inline fun <reified T> firstIs(items: List<Any>): Boolean = items.isNotEmpty() && items[0] is T

    internal inline fun twice(
        value: Int,
        step: (Int) -> Int,
    ): Int = if (value > 0) step(step(value)) else value

    fun doubledTwice(value: Int): Int = twice(value) { it * 2 }

    inline fun scaled(
        count: Count,
        step: (Int) -> Int,
    ): Int = if (count.value > 0) step(count.value) else 0

    fun scaledCount(text: String): Int = scaled(Count(text.length)) { it + 3 }

    fun clamped(value: Int): Int = clampTo(value, 10)

    fun clampTo(value: Int): Int = clampTo(value, 5)
}
