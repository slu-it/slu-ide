package dev.sluide.media

import java.util.Locale

/** The speeds offered for playback, slowest first: the media player's and Read Aloud's speed menus (ADR 0123, 0124). */
object PlaybackSpeeds {
    val ALL: List<Double> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

    /** `1×`, `0.75×`, `1.25×`: how the speed menus show [rate]. */
    fun label(rate: Double): String {
        val text = "%.2f".format(Locale.ROOT, rate).trimEnd('0').trimEnd('.')
        return "$text×"
    }
}
