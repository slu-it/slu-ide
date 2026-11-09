package dev.sluide.games.minigolf

/** The shot a drag aims at: [angle] in field radians (y down, 0 = +x), [power] in 0..1. */
internal data class Aim(
    val angle: Double,
    val power: Double,
)
