package dev.sluide.games.sudoku

data class Cell(val row: Int, val col: Int)

/** Grid size: [side]×[side] cells, boxes of [boxRows] rows × [boxCols] columns. */
enum class SudokuSize(val side: Int, val boxRows: Int, val boxCols: Int, val label: String) {
    FOUR(4, 2, 2, "4×4"),
    SIX(6, 2, 3, "6×6"),
    NINE(9, 3, 3, "9×9"),
    ;

    val key: String get() = side.toString()

    /** All cells, row-major. */
    val cells: List<Cell> by lazy { (0 until side * side).map { Cell(it / side, it % side) } }

    /** All rows (top to bottom), then all columns (left to right), then all boxes (row-major). */
    val units: List<List<Cell>> by lazy { unitIdx.map { unit -> unit.map { cells[it] } } }

    /** Cell indexes (row-major) of every unit, in the order of [units]. */
    internal val unitIdx: List<IntArray> by lazy {
        val boxesPerRow = side / boxCols
        val rows = (0 until side).map { r -> IntArray(side) { c -> r * side + c } }
        val cols = (0 until side).map { c -> IntArray(side) { r -> r * side + c } }
        val boxes =
            (0 until side).map { b ->
                val r0 = (b / boxesPerRow) * boxRows
                val c0 = (b % boxesPerRow) * boxCols
                IntArray(side) { k -> (r0 + k / boxCols) * side + c0 + k % boxCols }
            }
        rows + cols + boxes
    }

    /** Per cell index the ids (indexes into [unitIdx]) of its row, column and box. */
    internal val cellUnits: Array<IntArray> by lazy {
        Array(side * side) { i -> intArrayOf(i / side, side + i % side, 2 * side + boxIndex(i)) }
    }

    /** Per cell index the indexes of its peers. */
    internal val peerIdx: Array<IntArray> by lazy {
        Array(side * side) { i ->
            cellUnits[i].flatMap { u -> unitIdx[u].asList() }.filter { it != i }.distinct().toIntArray()
        }
    }

    private val peerSets: List<Set<Cell>> by lazy { peerIdx.map { p -> p.map { cells[it] }.toSet() } }

    /** Bits 1..side set. */
    internal val fullMask: Int get() = (1 shl (side + 1)) - 2

    fun peers(cell: Cell): Set<Cell> = peerSets[cell.row * side + cell.col]

    fun box(cell: Cell): Int = boxIndex(cell.row * side + cell.col)

    internal fun boxIndex(i: Int): Int = (i / side / boxRows) * (side / boxCols) + (i % side) / boxCols

    internal fun sees(
        a: Int,
        b: Int,
    ): Boolean = a != b && (a / side == b / side || a % side == b % side || boxIndex(a) == boxIndex(b))

    companion object {
        val DEFAULT = NINE

        fun ofKey(key: String?): SudokuSize? = entries.firstOrNull { it.key == key }
    }
}
