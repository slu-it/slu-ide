package dev.sluide.games.minigolf

/**
 * A hole on one big rectangular green (x 10..310, y 24..230, inside the margins) for the engine tests: tee at
 * (30, 125), cup at (290, 125), unless [build] moves them.
 */
internal fun testHole(
    number: Int = 1,
    par: Int = 3,
    build: HoleBuilder.() -> Unit = {},
): Hole =
    hole(number, par) {
        green(rect(10, 24, 310, 230))
        tee(30, 125)
        cup(290, 125)
        build()
    }
