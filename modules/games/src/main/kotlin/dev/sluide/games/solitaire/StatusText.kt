package dev.sluide.games.solitaire

import dev.sluide.games.common.formatTime
import java.util.Locale

/** The status texts of the Solitaire tab. */
internal object StatusText {
    const val DEALING = "Dealing…"
    const val FAILED = "Could not deal a game — F2 to try again"
    const val THINKING = "Thinking…"
    const val NO_HINT = "No hint found"
    const val FILL_COLUMNS = "Fill every empty column before dealing a row"
    const val CANT_MOVE = "Can't move there"

    fun looking(number: Int): String = "Looking for deal #$number…"

    fun noDealNear(number: Int): String = "No winnable deal found near #$number"

    fun undoBack(steps: Int): String =
        "No winning line from here — undo " + if (steps == 1) "1 move" else "$steps moves"

    /** "Deal #48213 · High score: 4,210", without the high score when there is none. */
    fun playing(
        number: Int,
        highScore: Int?,
    ): String = "Deal #$number" + (highScore?.let { " · High score: ${points(it)}" } ?: "")

    /** "Won with 4,210 points in 6:12", with " — new high score!" for a record. */
    fun won(
        score: Int,
        elapsedMillis: Long,
        newHigh: Boolean,
    ): String =
        "Won with ${points(score)} points in ${formatTime(elapsedMillis)}" + if (newHigh) " — new high score!" else ""

    private fun points(value: Int): String = String.format(Locale.US, "%,d", value)
}
