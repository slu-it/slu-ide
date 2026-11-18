package dev.sluide.pitestkotlin.fixtures

class StringEquals {
    fun isMain(branch: String): Boolean {
        if (branch == "main") return true
        return false
    }

    fun same(
        a: Any,
        b: Any,
    ): Boolean = a == b

    fun order(
        a: Long,
        b: Long,
    ): Int = a.compareTo(b)
}
