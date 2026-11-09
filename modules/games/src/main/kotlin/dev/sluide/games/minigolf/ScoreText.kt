package dev.sluide.games.minigolf

/** Formats scores. */
internal object ScoreText {
    /** A score relative to par: "E" for 0, "+2", "−1" (with the real minus sign). */
    fun toPar(n: Int): String =
        when {
            n == 0 -> "E"
            n > 0 -> "+$n"
            else -> "−${-n}"
        }
}
