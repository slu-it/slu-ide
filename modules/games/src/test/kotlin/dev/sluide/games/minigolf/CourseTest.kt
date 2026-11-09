package dev.sluide.games.minigolf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Structural checks ([CourseChecks]) and the reference solution of every hole of [COURSE]. */
internal class CourseTest {
    private fun hole(number: Int) = COURSE[number - 1]

    @Test
    fun `the holes are numbered from 1 in order`() {
        assertEquals((1..COURSE.size).toList(), COURSE.map { it.number })
    }

    @ParameterizedTest(name = "hole {0}")
    @MethodSource("holeNumbers")
    fun `the par is 2 to 4`(number: Int) {
        assertTrue(hole(number).par in 2..4, "par ${hole(number).par}")
    }

    @ParameterizedTest(name = "hole {0}")
    @MethodSource("holeNumbers")
    fun `the hole passes every structural check`(number: Int) {
        val hole = hole(number)
        val problems = CHECKS.flatMap { (name, check) -> check(hole).map { "$name: $it" } }
        assertEquals(emptyList<String>(), problems)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badHoles")
    fun `each check finds its problem in a bad hole`(
        name: String,
        bad: Hole,
    ) {
        val check = CHECKS.getValue(name)
        assertTrue(check(bad).isNotEmpty(), "$name passes a bad hole")
        assertEquals(emptyList<String>(), check(testHole()), "$name refuses the plain test hole")
    }

    @ParameterizedTest(name = "hole {0}")
    @MethodSource("holeNumbers")
    fun `the reference solution holes the ball in par`(number: Int) {
        val shots = SOLUTIONS[number] ?: fail("hole $number has no reference solution")
        val played = playShots(COURSE, number - 1, shots)
        assertTrue(played.holed, "not holed; the ball is at ${played.game.ball} after ${played.game.strokes} strokes")
        assertTrue(played.game.strokes <= hole(number).par, "${played.game.strokes} strokes, par ${hole(number).par}")
    }

    @Test
    fun `no reference solution belongs to a hole that does not exist`() {
        assertEquals(emptySet<Int>(), SOLUTIONS.keys - COURSE.map { it.number }.toSet())
    }

    private companion object {
        val CHECKS: Map<String, (Hole) -> List<String>> =
            mapOf(
                "margin" to CourseChecks::margin,
                "greens" to CourseChecks::greens,
                "tee and cup" to CourseChecks::teeAndCup,
                "tunnels" to CourseChecks::tunnels,
                "mover speed" to CourseChecks::moverSpeed,
                "mover tee" to CourseChecks::moverTee,
                "mover walls" to CourseChecks::moverWalls,
                "slopes drain" to CourseChecks::slopesDrain,
            )

        @JvmStatic
        fun holeNumbers() = COURSE.map { it.number }

        @JvmStatic
        fun badHoles() =
            listOf(
                Arguments.of(
                    "margin",
                    hole(1, 2) {
                        green(rect(2, 30, 100, 100))
                        tee(20, 60)
                        cup(80, 60)
                    },
                ),
                Arguments.of("greens", testHole { green(rect(310, 20, 314, 230)) }),
                Arguments.of("tee and cup", testHole { sand(rect(20, 115, 40, 135)) }),
                Arguments.of("tunnels", testHole { tunnel(100 to 125, 1 to 0, 250 to 22, 0 to 1) }),
                Arguments.of(
                    "tunnels",
                    testHole {
                        water(rect(90, 115, 110, 135))
                        tunnel(100 to 125, 1 to 0, 250 to 60, 0 to 1)
                    },
                ),
                Arguments.of("mover speed", testHole { rotatingBar(160 to 125, 60, 30) }),
                Arguments.of("mover tee", testHole { rotatingBar(30 to 140, 30, 240) }),
                // Larger than its path: no edge ever comes near the tee, but the block always covers it.
                Arguments.of("mover tee", testHole { slidingBlock(30 to 125, 34 to 125, 30, 30, 240) }),
                Arguments.of("mover walls", testHole { slidingBlock(150 to 28, 150 to 100, 10, 10, 240) }),
                Arguments.of(
                    "mover walls",
                    testHole {
                        bumper(200, 125, 6)
                        rotatingBar(180 to 125, 20, 240)
                    },
                ),
                // The lower part slopes into the bottom rail: a pocket the ball never leaves.
                Arguments.of("slopes drain", testHole { slope(rect(100, 150, 200, 230), 0 to 50) }),
            )
    }
}
