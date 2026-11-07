package dev.sluide.games.blockbreaker

/** [hits] is the number of hits that destroy the brick; STEEL (0) never breaks. */
internal enum class BrickKind(
    val hits: Int,
) {
    NORMAL(1),
    TOUGH(2),
    HARD(3),
    STEEL(0),
}

/** The outcome of one ball hit; [breakable] is false for steel, [points] is set only when [destroyed]. */
internal data class BrickHitResult(
    val column: Int,
    val row: Int,
    val breakable: Boolean,
    val destroyed: Boolean,
    val points: Int,
)

/**
 * The brick field: 13 columns of 24 x 8 px from x = 4, rows from y = 32, at most 10 rows.
 * Layout characters: `.` empty, `1`-`5` NORMAL with that colour tier, `T` TOUGH, `H` HARD, `#` STEEL.
 */
internal class BrickGrid {
    private class Brick(
        val column: Int,
        val row: Int,
        val kind: BrickKind,
        val tier: Int,
        var hitsLeft: Int,
    ) {
        val points: Int
            get() =
                when (kind) {
                    BrickKind.NORMAL -> 10 * tier
                    BrickKind.TOUGH -> 60
                    BrickKind.HARD -> 90
                    BrickKind.STEEL -> 0
                }
    }

    private val cells = arrayOfNulls<Brick>(Rules.COLUMNS * Rules.MAX_ROWS)

    val bricks: List<BrickState>
        get() = cells.filterNotNull().map { BrickState(it.column, it.row, it.kind, it.tier, it.hitsLeft) }

    val breakableLeft: Int get() = cells.count { it != null && it.kind != BrickKind.STEEL }

    fun load(rows: List<String>) {
        require(rows.size <= Rules.MAX_ROWS) { "at most ${Rules.MAX_ROWS} rows" }
        cells.fill(null)
        rows.forEachIndexed { row, text ->
            require(text.length == Rules.COLUMNS) { "row $row must have ${Rules.COLUMNS} characters" }
            text.forEachIndexed { column, char -> parse(char, column, row)?.let { cells[index(column, row)] = it } }
        }
    }

    fun hasBrick(
        column: Int,
        row: Int,
    ): Boolean = at(column, row) != null

    fun hit(
        column: Int,
        row: Int,
    ): BrickHitResult? {
        val brick = at(column, row) ?: return null
        if (brick.kind == BrickKind.STEEL) return BrickHitResult(column, row, false, false, 0)
        brick.hitsLeft--
        if (brick.hitsLeft > 0) return BrickHitResult(column, row, true, false, 0)
        cells[index(column, row)] = null
        return BrickHitResult(column, row, true, true, brick.points)
    }

    private fun at(
        column: Int,
        row: Int,
    ): Brick? =
        if (column !in 0 until Rules.COLUMNS ||
            row !in 0 until Rules.MAX_ROWS
        ) {
            null
        } else {
            cells[index(column, row)]
        }

    private fun index(
        column: Int,
        row: Int,
    ) = row * Rules.COLUMNS + column

    private fun parse(
        char: Char,
        column: Int,
        row: Int,
    ): Brick? =
        when (char) {
            '.' -> null
            in '1'..'5' -> Brick(column, row, BrickKind.NORMAL, char - '0', 1)
            'T' -> Brick(column, row, BrickKind.TOUGH, 0, BrickKind.TOUGH.hits)
            'H' -> Brick(column, row, BrickKind.HARD, 0, BrickKind.HARD.hits)
            '#' -> Brick(column, row, BrickKind.STEEL, 0, 0)
            else -> throw IllegalArgumentException("unknown brick character '$char'")
        }
}
