package dev.sluide.games.solitaire

/** The parts of the score that depend on the mode and the time, as pure functions (ADR 0138). */
internal object Scoring {
    private const val TIME_STEP_MILLIS = 10_000L
    private const val BONUS_MIN_SECONDS = 30L
    private const val BONUS_POINTS = 700_000L
    private const val SPIDER_BASE = 500

    /** The score a game starts with: Spider 500, the others 0. */
    fun base(mode: Mode): Int = if (mode.variant == Variant.SPIDER) SPIDER_BASE else 0

    /** Klondike: -2 per full 10 s of play; the other games 0. */
    fun timePoints(
        mode: Mode,
        elapsedMillis: Long,
    ): Int = if (mode.variant == Variant.KLONDIKE) (-2 * (elapsedMillis / TIME_STEP_MILLIS)).toInt() else 0

    /**
     * Added when the game is won: Klondike and FreeCell 700,000 / whole seconds (integer division) if the game took
     * at least 30 s (23,333 at exactly 30 s), else 0; Spider 0.
     */
    fun timeBonus(
        mode: Mode,
        elapsedMillis: Long,
    ): Int {
        if (mode.variant == Variant.SPIDER) return 0
        val seconds = elapsedMillis / 1000
        return if (seconds >= BONUS_MIN_SECONDS) (BONUS_POINTS / seconds).toInt() else 0
    }

    /** Klondike never goes below 0; FreeCell and Spider are unbounded. */
    fun floor(
        mode: Mode,
        raw: Int,
    ): Int = if (mode.variant == Variant.KLONDIKE) maxOf(0, raw) else raw
}
