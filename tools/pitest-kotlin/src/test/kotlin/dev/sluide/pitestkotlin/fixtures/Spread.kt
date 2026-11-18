package dev.sluide.pitestkotlin.fixtures

class Spread {
    fun all(items: Array<String>): List<String> = listOf(*items)

    fun copy(items: Array<String>): Array<String> = items.copyOf()

    fun explicitCopy(items: Array<String>): Array<String> = java.util.Arrays.copyOf(items, items.size)

    fun withMore(items: Array<String>): List<String> = listOf(*items, "x")

    fun ofCall(): List<String> = listOf(*names())

    private fun names() = arrayOf("a")

    fun otherCopies(items: Array<String>): List<Array<String>> =
        listOf(java.util.Arrays.copyOf(names(), 2), java.util.Arrays.copyOf(names(), items.size))

    fun sizedLike(
        items: Array<String>,
        other: Array<String>,
    ): List<String> = listOf(*java.util.Arrays.copyOf(items, other.size))

    fun halfCopy(items: Array<String>): List<String> = listOf(*java.util.Arrays.copyOf(items, half(items)))

    fun fillWithSize(numbers: IntArray) = java.util.Arrays.fill(numbers, numbers.size)
}

private fun half(items: Array<String>) = items.size / 2
