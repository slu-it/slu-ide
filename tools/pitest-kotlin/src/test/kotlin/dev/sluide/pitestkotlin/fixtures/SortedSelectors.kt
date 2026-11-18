package dev.sluide.pitestkotlin.fixtures

class SortedSelectors {
    fun byLength(names: List<String>): List<String> = names.sortedBy { it.length }

    fun byLengthThenName(names: List<String>): List<String> =
        names.sortedWith(compareBy<String> { it.length }.thenBy { it.lowercase() })
}
