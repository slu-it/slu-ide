package dev.sluide.pitestkotlin.fixtures

class InlineCollections {
    fun lengthsOfLong(names: List<String>): List<Int> =
        names
            .filter { name ->
                if (name.length > 3) true else name.startsWith("x")
            }.map { it.length }

    fun lengths(names: List<String>): List<Int> = names.map(String::length)

    fun shortOnes(names: List<String>): List<String> = names.filter(::isShort)

    private fun isShort(name: String) = name.length < 3
}
