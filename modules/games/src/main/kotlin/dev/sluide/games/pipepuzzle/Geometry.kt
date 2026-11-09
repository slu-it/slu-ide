package dev.sluide.games.pipepuzzle

/** A side of a cell; [bit] is its bit in [Openings]. */
enum class Dir(
    val bit: Int,
    val dRow: Int,
    val dCol: Int,
) {
    N(1, -1, 0),
    E(2, 0, 1),
    S(4, 1, 0),
    W(8, 0, -1),
    ;

    val opposite: Dir get() = entries[(ordinal + 2) % 4]
}

/** The sides of a tile that have a pipe end: N = 1, E = 2, S = 4, W = 8. */
@JvmInline
value class Openings(
    val bits: Int,
) {
    init {
        require(bits in 0..ALL) { "openings out of range: $bits" }
    }

    fun has(dir: Dir): Boolean = bits and dir.bit != 0

    /** Turned a quarter clockwise: N → E → S → W → N. */
    fun rotatedCw(): Openings = Openings(((bits shl 1) or (bits ushr 3)) and ALL)

    fun rotatedCcw(): Openings = Openings(((bits ushr 1) or (bits shl 3)) and ALL)

    val count: Int get() = Integer.bitCount(bits)

    val isCross: Boolean get() = bits == ALL

    val dirs: List<Dir> get() = Dir.entries.filter { has(it) }

    fun with(dir: Dir): Openings = Openings(bits or dir.bit)

    /** The distinct orientations reachable by turning, starting with this one (a straight has 2, a cross 1). */
    val rotations: List<Openings>
        get() = generateSequence(this) { it.rotatedCw() }.take(4).distinct().toList()

    override fun toString(): String = "Openings(${dirs.joinToString("") { it.name }})"

    companion object {
        private const val ALL = 15
        val NONE = Openings(0)
        val CROSS = Openings(ALL)

        fun of(vararg dirs: Dir): Openings = Openings(dirs.fold(0) { bits, dir -> bits or dir.bit })
    }
}

data class Pos(
    val row: Int,
    val col: Int,
) {
    fun step(dir: Dir): Pos = Pos(row + dir.dRow, col + dir.dCol)

    fun distanceTo(other: Pos): Int = Math.abs(row - other.row) + Math.abs(col - other.col)
}

internal fun Pos.inBoard(side: Int): Boolean = row in 0 until side && col in 0 until side

internal fun Pos.index(side: Int): Int = row * side + col

internal fun posOf(
    index: Int,
    side: Int,
): Pos = Pos(index / side, index % side)
