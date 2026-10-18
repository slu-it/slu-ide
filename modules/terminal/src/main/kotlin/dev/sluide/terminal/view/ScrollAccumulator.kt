package dev.sluide.terminal.view

/** Lines a wheel notch scrolls at 100 %, as in most terminals. */
internal const val SCROLL_LINES = 3

/** How fast a terminal scrolls, in percent of the default speed, for trackpads and mouse wheels. */
internal data class ScrollSpeeds(
    val trackpad: Int = 100,
    val wheel: Int = 100,
)

/**
 * Turns scroll events into whole steps, each event worth [perEvent] steps at 100 %, keeping the fraction for the next
 * event in the same direction. Counts in hundredths of a step, so e.g. ten events at 10 % add up to exactly
 * [perEvent].
 */
internal class ScrollAccumulator(
    private val perEvent: Int = SCROLL_LINES,
) {
    private var hundredths = 0
    private var up = false

    /** Adds an event at [percent] speed; returns the whole steps to scroll, negative towards the history (up). */
    fun add(
        up: Boolean,
        percent: Int,
    ): Int {
        if (up != this.up) {
            hundredths = 0
            this.up = up
        }
        hundredths += perEvent * percent
        val steps = hundredths / 100
        hundredths %= 100
        return if (up) -steps else steps
    }

    /** Drops the fraction left over. */
    fun reset() {
        hundredths = 0
    }
}
