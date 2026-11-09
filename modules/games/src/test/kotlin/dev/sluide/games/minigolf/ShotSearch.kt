package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Vec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds a reference solution for a hole: a beam search over a grid of [angleCount] angles × [powers] (× wait steps on
 * holes with moving obstacles), up to par shots. After each shot the end positions are ranked by their walking
 * distance to the cup over the felt (around blocks, water and through tunnels), and the best [beamWidth] distinct ones
 * are searched further. Among the shots that hole the ball it keeps the one with the most holing neighbours in the
 * grid, so that the solution does not hang on a hair.
 *
 * Not run by the build: [ShotSearchTest] runs only with the environment variable `MINIGOLF_SEARCH` set to hole
 * numbers (`4,5,6`) or `all`, for example from the repository root:
 * ```
 * MINIGOLF_SEARCH=4,5 ./gradlew :modules:games:test --tests 'dev.sluide.games.minigolf.ShotSearchTest' --rerun
 * ```
 * It writes `RefShot` literals, ready to paste into the solutions map, to `modules/games/build/shot-search.txt` (and
 * to the test's standard output). A hole without moving obstacles takes a few seconds; one with them tries 12 wait
 * steps per shot and takes about ten times longer. `MINIGOLF_SEARCH_BEAM` sets a wider beam for a hard hole.
 */
internal class ShotSearch(
    private val course: List<Hole> = COURSE,
    private val angleCount: Int = 144,
    private val powers: List<Double> = (2..40).map { it * 25 / 1000.0 },
    private val beamWidth: Int = 6,
) {
    private class Candidate(
        val shots: List<RefShot>,
        val played: Played,
        val distance: Double,
    )

    /** A solution of the hole at [index] in at most [maxShots] shots, or null. */
    fun solve(
        index: Int,
        maxShots: Int = course[index].par,
    ): List<RefShot>? {
        val hole = course[index]
        val field = DistanceField(hole)
        val waits = waitSteps(hole)
        var beam = listOf(emptyList<RefShot>())
        repeat(maxShots) {
            val next = mutableListOf<Candidate>()
            for (prefix in beam) {
                val holing = mutableMapOf<Triple<Int, Int, Int>, List<RefShot>>()
                for ((w, wait) in waits.withIndex()) {
                    for (a in 0 until angleCount) {
                        val angle = Math.round(2 * Math.PI * a / angleCount * 10000) / 10000.0
                        for ((p, power) in powers.withIndex()) {
                            val shots = prefix + RefShot(wait, angle, power)
                            val played = playShots(course, index, shots)
                            when {
                                played.holed && played.game.strokes <= maxShots -> {
                                    holing[Triple(w, a, p)] = shots
                                }

                                played.game.phase == Phase.AIMING && played.game.strokes < maxShots -> {
                                    next += Candidate(shots, played, field.at(played.game.ball))
                                }
                            }
                        }
                    }
                }
                if (holing.isNotEmpty()) return mostRobust(holing)
            }
            beam =
                next
                    .sortedWith(compareBy({ it.distance }, { it.played.game.strokes }))
                    .distinctBy { (it.played.game.ball.x / 4).roundToInt() to (it.played.game.ball.y / 4).roundToInt() }
                    .take(beamWidth)
                    .map { it.shots }
            if (beam.isEmpty()) return null
        }
        return null
    }

    private fun mostRobust(holing: Map<Triple<Int, Int, Int>, List<RefShot>>): List<RefShot> =
        holing
            .maxBy { (key, _) ->
                val (w, a, p) = key
                var neighbours = 0
                for (da in -2..2) {
                    for (dp in -1..1) {
                        if (holing.containsKey(Triple(w, Math.floorMod(a + da, angleCount), p + dp))) neighbours++
                    }
                }
                neighbours
            }.value

    private fun waitSteps(hole: Hole): List<Int> {
        if (hole.movers.isEmpty()) return listOf(0)
        val period =
            hole.movers.maxOf {
                when (it) {
                    is RotatingBar -> Math.abs(it.periodSteps)
                    is SlidingBlock -> it.periodSteps
                }
            }
        return (0 until 12).map { it * period / 12 }.distinct()
    }

    /** Walking distance to the cup over the felt, on a grid of 2 units; sand counts three times. */
    private class DistanceField(
        private val hole: Hole,
    ) {
        private val cell = 2.0
        private val columns = (Rules.FIELD_WIDTH / cell).toInt()
        private val rows = (Rules.FIELD_HEIGHT / cell).toInt()
        private val distances = DoubleArray(columns * rows) { Double.MAX_VALUE }
        private val physics = BallPhysics(hole)

        init {
            val open = BooleanArray(columns * rows) { isOpen(center(it % columns, it / columns)) }
            val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
            val start = indexOf(hole.cup)
            distances[start] = 0.0
            queue += 0.0 to start
            while (queue.isNotEmpty()) {
                val (distance, index) = queue.poll()
                if (distance > distances[index]) continue
                val column = index % columns
                val row = index / columns
                val position = center(column, row)
                hole.tunnels.forEach { tunnel ->
                    // Backwards: a ball entering one mouth comes out of the other.
                    if (position.distanceTo(tunnel.b) <= Rules.TUNNEL_RADIUS) relax(queue, indexOf(tunnel.a), distance)
                    if (position.distanceTo(tunnel.a) <= Rules.TUNNEL_RADIUS) relax(queue, indexOf(tunnel.b), distance)
                }
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val c = column + dx
                        val r = row + dy
                        if ((dx == 0 && dy == 0) || c !in 0 until columns || r !in 0 until rows) continue
                        val neighbour = r * columns + c
                        if (!open[neighbour]) continue
                        val sand = if (physics.inSand(center(c, r))) 3.0 else 1.0
                        relax(queue, neighbour, distance + cell * sqrt((dx * dx + dy * dy).toDouble()) * sand)
                    }
                }
            }
        }

        fun at(p: Vec): Double = distances[indexOf(p)]

        private fun relax(
            queue: PriorityQueue<Pair<Double, Int>>,
            index: Int,
            distance: Double,
        ) {
            if (distance < distances[index]) {
                distances[index] = distance
                queue += distance to index
            }
        }

        private fun isOpen(p: Vec) =
            hole.greens.any { it.contains(p) } &&
                hole.blocks.none { it.contains(p) } &&
                !physics.inWater(p) &&
                hole.walls.all { it.distanceTo(p) >= Rules.BALL_RADIUS } &&
                hole.bumpers.all { it.center.distanceTo(p) >= it.radius + Rules.BALL_RADIUS }

        private fun center(
            column: Int,
            row: Int,
        ) = Vec((column + 0.5) * cell, (row + 0.5) * cell)

        private fun indexOf(p: Vec): Int {
            val column = (p.x / cell).toInt().coerceIn(0, columns - 1)
            val row = (p.y / cell).toInt().coerceIn(0, rows - 1)
            return row * columns + column
        }
    }
}

/** Runs [ShotSearch] on demand only; see its KDoc. */
@EnabledIfEnvironmentVariable(named = "MINIGOLF_SEARCH", matches = ".+")
internal class ShotSearchTest {
    @Test
    fun `prints reference solutions`() {
        val wanted = System.getenv("MINIGOLF_SEARCH").trim()
        val beam = System.getenv("MINIGOLF_SEARCH_BEAM")?.toIntOrNull() ?: 6
        val holes =
            when (wanted) {
                "all" -> COURSE
                else -> wanted.split(',').map { number -> COURSE[number.trim().toInt() - 1] }
            }
        val search = ShotSearch(beamWidth = beam)
        val lines =
            holes.map { hole ->
                val started = System.nanoTime()
                val solution = search.solve(hole.number - 1)
                val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                val literal =
                    solution?.joinToString(prefix = "listOf(", postfix = ")") {
                        "RefShot(${it.waitSteps}, ${it.angle}, ${it.power})"
                    } ?: "null"
                "        ${hole.number} to $literal, // par ${hole.par}, ${"%.1f".format(seconds)} s"
            }
        val output = lines.joinToString("\n")
        println(output)
        Files.writeString(Path.of("build", "shot-search.txt"), output + "\n")
    }
}
