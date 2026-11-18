package dev.sluide.pitestkotlin.fixtures

class WhenSubject {
    fun weight(size: Int): String =
        when (size) {
            0 -> "none"
            1, 2 -> "few"
            else -> "many"
        }

    fun kind(name: String): Int =
        when (name) {
            "main" -> 1
            "master" -> 2
            else -> 0
        }
}
