package dev.sluide.pitestkotlin.fixtures

inline fun clampTo(
    value: Int,
    max: Int,
): Int = if (value > max) max else value
