package dev.sluide.pitestkotlin.fixtures

class DefaultArguments {
    fun greet(
        name: String = "you",
        times: Int = 1,
    ): String = name.repeat(times)
}
