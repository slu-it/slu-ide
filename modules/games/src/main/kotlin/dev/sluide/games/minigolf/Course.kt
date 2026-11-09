package dev.sluide.games.minigolf

/** The first three holes, one idea each: a lane with a side pocket, a dogleg, a bank shot around a block. */
internal val OPENING_HOLES: List<Hole> =
    listOf(
        hole(1, par = 2) {
            green(30 to 104, 290 to 104, 290 to 152, 284 to 152, 284 to 196, 244 to 196, 244 to 152, 30 to 152)
            block(rect(150, 114, 160, 142))
            tee(50, 128)
            cup(264, 178)
        },
        hole(2, par = 2) {
            green(30 to 170, 240 to 170, 240 to 40, 290 to 40, 290 to 220, 30 to 220)
            tee(55, 195)
            cup(265, 65)
        },
        hole(3, par = 3) {
            green(20 to 226, 20 to 80, 70 to 30, 250 to 30, 300 to 80, 300 to 226)
            block(140 to 226, 140 to 46, 180 to 46, 180 to 226)
            tee(45, 200)
            cup(272, 200)
        },
    )

/**
 * The course, played in order: holes 1-3 here, 4-9 in [FRONT_HOLES], 10-18 in [BACK_HOLES]. Declared after
 * [OPENING_HOLES], since a file's properties are initialized in their order.
 */
internal val COURSE: List<Hole> = OPENING_HOLES + FRONT_HOLES + BACK_HOLES
