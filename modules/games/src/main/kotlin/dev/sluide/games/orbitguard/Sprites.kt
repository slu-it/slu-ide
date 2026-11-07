package dev.sluide.games.orbitguard

/** A bitmap drawn as rows of '#' (set) and '.' (empty); also the hit shape of what it draws. */
internal class Sprite(
    val rows: List<String>,
) {
    val width: Int = rows.first().length
    val height: Int = rows.size

    init {
        require(rows.all { it.length == width }) { "rows of different width" }
    }

    fun isSet(
        x: Int,
        y: Int,
    ): Boolean = y in 0 until height && x in 0 until width && rows[y][x] == '#'
}

/**
 * Orbit Guard's own pixel art: a downward arrowhead, a bat-wing and a tracked tank, a twin-prow fighter and a
 * torpedo pod.
 */
internal object Sprites {
    val SHIP =
        Sprite(
            listOf(
                "....#...#....",
                "....#...#....",
                "...##...##...",
                "..###.#.###..",
                ".####...####.",
                "#####.#.#####",
                "#############",
                "##.........##",
            ),
        )

    private val SCOUT_FRAMES =
        listOf(
            Sprite(
                listOf(
                    "##.....##",
                    "#.#...#.#",
                    ".#######.",
                    ".#.....#.",
                    "..#...#..",
                    "..#...#..",
                    "...#.#...",
                    "....#....",
                ),
            ),
            Sprite(
                listOf(
                    "#.......#",
                    ".#.....#.",
                    ".#######.",
                    ".#.....#.",
                    "..#...#..",
                    "..#...#..",
                    "...#.#...",
                    "....#....",
                ),
            ),
        )

    private val RAIDER_FRAMES =
        listOf(
            Sprite(
                listOf(
                    "#....###....#",
                    "##..#####..##",
                    "###.#####.###",
                    "######.######",
                    ".###########.",
                    "..#########..",
                    "....#####....",
                    "......#......",
                ),
            ),
            Sprite(
                listOf(
                    ".....###.....",
                    "...#######...",
                    ".###########.",
                    "######.######",
                    "###.#####.###",
                    "##...###...##",
                    "#....###....#",
                    "......#......",
                ),
            ),
        )

    private val BRUTE_FRAMES =
        listOf(
            Sprite(
                listOf(
                    "......##......",
                    ".....####.....",
                    "..##########..",
                    "####......####",
                    "#.#.#.#.#.#.#.",
                    "##############",
                    "##############",
                ),
            ),
            Sprite(
                listOf(
                    "......##......",
                    ".....####.....",
                    "..##########..",
                    "####......####",
                    ".#.#.#.#.#.#.#",
                    "##############",
                    "##############",
                ),
            ),
        )

    val BONUS =
        Sprite(
            listOf(
                "..#..........#..",
                ".##...####...##.",
                "#.############.#",
                "################",
                "#.############.#",
                ".##...####...##.",
                "..#..........#..",
            ),
        )

    /** A chamfered hexagonal bastion: corners cut diagonally by 4 px at top and bottom, solid inside, no arch. */
    val SHIELD =
        Sprite(
            List(16) { row ->
                val cut =
                    if (row < 4) {
                        4 - row
                    } else if (row >= 12) {
                        row - 11
                    } else {
                        0
                    }
                ".".repeat(cut) + "#".repeat(24 - 2 * cut) + ".".repeat(cut)
            },
        )

    val ENEMY_EXPLOSION =
        Sprite(
            listOf(
                "#...#..#...#",
                ".#..#..#..#.",
                "..#..##..#..",
                "#..#.##.#..#",
                ".#...##...#.",
                "..#.#..#.#..",
                ".#..#..#..#.",
                "#....##....#",
            ),
        )

    val SHIP_EXPLOSION =
        Sprite(
            listOf(
                "#..#....#....#.",
                ".#..#..#.#..#..",
                "..#..##.##..#..",
                "#.#.#######.#..",
                ".#..#########.#",
                "..#.#.#####.#..",
                ".#..#..#.#..#.#",
                "#...#.#...#..#.",
            ),
        )

    val SHOT_EXPLOSION =
        Sprite(
            listOf(
                "#..#.#",
                ".#.#..",
                "#.##.#",
                ".#.##.",
                "..#..#",
                "#.#.#.",
            ),
        )

    /** [frame] is 0 or 1 (the game's animationFrame). */
    fun enemy(
        kind: EnemyKind,
        frame: Int,
    ): Sprite =
        when (kind) {
            EnemyKind.SCOUT -> SCOUT_FRAMES
            EnemyKind.RAIDER -> RAIDER_FRAMES
            EnemyKind.BRUTE -> BRUTE_FRAMES
        }[frame and 1]
}
