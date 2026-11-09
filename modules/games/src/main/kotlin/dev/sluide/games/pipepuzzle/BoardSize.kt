package dev.sluide.games.pipepuzzle

/** A square board of [side] × [side] cells, with closed edges. */
enum class BoardSize(
    val side: Int,
) {
    S5(5),
    S7(7),
    S9(9),
    S12(12),
}
