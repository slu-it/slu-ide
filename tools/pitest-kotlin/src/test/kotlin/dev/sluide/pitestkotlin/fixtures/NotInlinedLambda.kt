package dev.sluide.pitestkotlin.fixtures

class NotInlinedLambda {
    fun positive(): (Int) -> Boolean = { value -> if (value > 0) true else value == -1 }
}
