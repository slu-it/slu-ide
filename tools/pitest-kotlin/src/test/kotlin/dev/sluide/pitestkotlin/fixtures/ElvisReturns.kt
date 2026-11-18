package dev.sluide.pitestkotlin.fixtures

class ElvisReturns {
    fun lengthOrZero(text: String?): Int {
        val known = text ?: return 0
        return known.length
    }

    fun upper(text: String?): String? {
        val known = text ?: return null
        return known.uppercase()
    }
}
