package dev.sluide.games.orbitguard

/** Picks the nearest of several candidate hits of one shot: the largest key wins, or the smallest. */
internal class Nearest(
    private val largerWins: Boolean,
) {
    private var bestKey = if (largerWins) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    private var action: (() -> Unit)? = null

    fun consider(
        key: Double,
        act: () -> Unit,
    ) {
        if (if (largerWins) key > bestKey else key < bestKey) {
            bestKey = key
            action = act
        }
    }

    /** Runs the nearest candidate's action; false when there was none. */
    fun run(): Boolean {
        val act = action ?: return false
        act()
        return true
    }
}

/** Whether a 1 px wide shot at [shotX], covering [sweep] rows from [top], overlaps the given box. */
internal fun sweptHits(
    shotX: Double,
    top: Double,
    sweep: Int,
    x: Double,
    y: Double,
    w: Int,
    h: Int,
) = Rules.overlaps(shotX, top, Rules.SHOT_WIDTH, sweep, x, y, w, h)
