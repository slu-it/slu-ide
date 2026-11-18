package dev.sluide.pitestkotlin.fixtures

class ResultCalls {
    fun parse(text: String): Int? = runCatching { text.toInt() }.getOrNull()

    fun attempt(text: String): Result<Int> = runCatching { text.toInt() }

    fun parses(text: String): Boolean = attempt(text).isSuccess

    fun boxed(text: String): Any = attempt(text)

    fun firstFailed(results: List<Result<Int>>): Boolean = results.first().isFailure

    fun count(text: String): Count = Count(text.length)
}

@JvmInline
value class Count(
    val value: Int,
) {
    init {
        require(value >= 0)
    }
}
