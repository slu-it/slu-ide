package dev.sluide.pitestkotlin.fixtures

class Properties(
    val name: String,
) {
    var count: Int = 0

    val doubled: Int
        get() = if (count > 10) 10 else count * 2

    private val hidden = 3

    // Private: no null check of the parameter, so its code is just the load of another instance's field.
    @Suppress("unused")
    private fun hiddenOf(other: Properties): Int = other.hidden

    fun code(): Int = hashCode()

    fun stamp(): Long = System.nanoTime()

    companion object {
        val defaults: List<String> = listOf("a")
        private val failure = IllegalStateException("failed")

        fun fail(): Nothing = throw failure
    }
}

object PropertyDefaults {
    val names: List<String> = listOf("b")
}
