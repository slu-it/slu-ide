package dev.sluide.games.sudoku

/** Immutable; digits 1..side, 0 = empty. */
class Grid private constructor(
    val size: SudokuSize,
    private val digits: IntArray,
) {
    private fun indexOf(cell: Cell): Int {
        require(cell.row in 0 until size.side && cell.col in 0 until size.side) { "$cell is outside the grid" }
        return cell.row * size.side + cell.col
    }

    operator fun get(cell: Cell): Int = digits[indexOf(cell)]

    fun with(
        cell: Cell,
        digit: Int,
    ): Grid {
        require(digit in 0..size.side) { "digit $digit out of range" }
        val copy = digits.copyOf()
        copy[indexOf(cell)] = digit
        return Grid(size, copy)
    }

    val filledCount: Int get() = digits.count { it != 0 }

    val isFull: Boolean get() = digits.all { it != 0 }

    /** Empty for a filled cell; the digits not used by any peer otherwise. */
    fun candidates(cell: Cell): Set<Int> {
        val i = indexOf(cell)
        if (digits[i] != 0) return emptySet()
        var used = 0
        for (p in size.peerIdx[i]) used = used or (1 shl digits[p])
        return (1..size.side).filter { used and (1 shl it) == 0 }.toSet()
    }

    /** True when a digit occurs twice in a unit. */
    fun hasConflict(): Boolean =
        size.unitIdx.any { unit ->
            var seen = 0
            unit.any { i ->
                val d = digits[i]
                val dup = d != 0 && seen and (1 shl d) != 0
                if (d != 0) seen = seen or (1 shl d)
                dup
            }
        }

    internal fun digitAt(index: Int): Int = digits[index]

    internal fun digitsCopy(): IntArray = digits.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Grid && other.size == size && other.digits.contentEquals(digits)

    override fun hashCode(): Int = 31 * size.hashCode() + digits.contentHashCode()

    override fun toString(): String =
        (0 until size.side).joinToString("\n") { r ->
            (0 until size.side).joinToString("") { c ->
                digits[r * size.side + c].let { if (it == 0) "." else it.toString() }
            }
        }

    companion object {
        fun empty(size: SudokuSize): Grid = Grid(size, IntArray(size.side * size.side))

        internal fun ofDigits(
            size: SudokuSize,
            digits: IntArray,
        ): Grid = Grid(size, digits)

        /** One string per row, '1'..'9' for digits, '.' or '0' for empty cells. */
        fun of(
            size: SudokuSize,
            rows: List<String>,
        ): Grid {
            require(rows.size == size.side) { "expected ${size.side} rows, got ${rows.size}" }
            val digits = IntArray(size.side * size.side)
            rows.forEachIndexed { r, row ->
                require(row.length == size.side) { "row $r has ${row.length} characters, expected ${size.side}" }
                row.forEachIndexed { c, ch ->
                    val d = if (ch == '.') 0 else ch - '0'
                    require(d in 0..size.side) { "bad character '$ch' in row $r" }
                    digits[r * size.side + c] = d
                }
            }
            return Grid(size, digits)
        }
    }
}
