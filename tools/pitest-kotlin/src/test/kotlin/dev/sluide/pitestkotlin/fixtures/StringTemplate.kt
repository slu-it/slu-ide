package dev.sluide.pitestkotlin.fixtures

class StringTemplate {
    fun label(
        count: Int,
        owner: Any,
    ): String = "$owner has $count"
}
