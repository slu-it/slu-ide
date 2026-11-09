package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Dir
import dev.sluide.games.pipepuzzle.Openings
import dev.sluide.games.pipepuzzle.Pos

/** Everything [TilePainter] needs to draw one frame of the board. */
internal data class BoardPaint(
    val side: Int,
    /** Row-major, the current openings. */
    val tiles: List<Openings>,
    val source: Pos,
    val drain: Pos,
    /** Drawn with the flow; during the win animation a growing subset. */
    val filled: Set<Pos>,
    /** Leak marks on these openings. */
    val leaks: List<Pair<Pos, Dir>>,
    /** Drawn only when non-null. */
    val cursor: Pos?,
    /** The tile being turned: drawn rotated by [turnAngle] degrees (e.g. -90 to 0 for a clockwise turn just made); its leak marks are not drawn while [turnAngle] is not 0. */
    val turning: Pos? = null,
    val turnAngle: Double = 0.0,
    /** A glow on the drain once solved. */
    val solved: Boolean = false,
)
