package dev.sluide.pitestkotlin.fixtures

class NullChecks {
    fun total(
        text: String,
        other: String?,
    ): Int = text.length + other!!.length

    fun home(): String {
        val home: String = System.getProperty("user.home")
        return home.trim()
    }
}
