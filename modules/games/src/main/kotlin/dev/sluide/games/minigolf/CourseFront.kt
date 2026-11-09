package dev.sluide.games.minigolf

/**
 * Holes 4-9 of [COURSE], one new element each: sand, a slope, bumpers, water, a rotating bar and a tunnel.
 */
internal val FRONT_HOLES: List<Hole> =
    listOf(
        // A Z of three lanes; sand across the last one, with a gap on each side, guards the cup.
        hole(4, par = 2) {
            green(24 to 40, 200 to 40, 200 to 160, 296 to 160, 296 to 215, 100 to 215, 100 to 100, 24 to 100)
            sand(rect(215, 172, 250, 203))
            tee(50, 70)
            cup(272, 188)
        },
        // A U of three lanes; a slope across the whole far lane pulls the ball back down, so it must climb it. The pull is
        // 70, stronger than the 50 of the Slope KDoc: a weak shot dies on the slope and rolls back, never resting on it.
        hole(5, par = 2) {
            green(24 to 215, 296 to 215, 296 to 40, 24 to 40, 24 to 95, 240 to 95, 240 to 160, 24 to 160)
            slope(rect(240, 100, 296, 150), 0 to 70)
            tee(45, 188)
            cup(50, 67)
        },
        // Bumpers in the middle of the octagon, a few more beside the tee and the lanes, and a wall that shields the cup.
        hole(6, par = 3) {
            green(24 to 90, 60 to 40, 260 to 40, 296 to 90, 296 to 170, 260 to 220, 60 to 220, 24 to 170)
            // The main field: a diamond of five.
            bumper(110, 95, 9)
            bumper(110, 165, 9)
            bumper(165, 130, 10)
            bumper(220, 90, 9)
            bumper(220, 170, 9)
            // Small bumpers that spoil the straight banks.
            bumper(80, 130, 6)
            bumper(70, 62, 7)
            bumper(150, 200, 8)
            bumper(205, 128, 7)
            // A tooth hanging from the top rail, between the field and the cup.
            block(200 to 40, 212 to 40, 212 to 60, 200 to 60)
            // The wall in front of the cup; the cup pocket is open only at the top right.
            block(240 to 72, 252 to 72, 252 to 188, 240 to 188)
            // Closes the cup pocket from below.
            block(252 to 140, 296 to 140, 296 to 170, 252 to 170)
            tee(45, 130)
            cup(274, 108)
        },
        // Two platforms joined by a Z-shaped bridge of felt between two pools.
        hole(7, par = 3) {
            green(rect(24, 40, 296, 210))
            water(100 to 40, 220 to 40, 220 to 142, 170 to 142, 170 to 62, 100 to 62)
            water(100 to 98, 134 to 98, 134 to 178, 220 to 178, 220 to 210, 100 to 210)
            tee(50, 80)
            cup(270, 170)
        },
        // Two rooms joined by a neck with a turning bar; a block in each room means the shot must be banked and timed.
        hole(8, par = 3) {
            green(
                24 to 50,
                130 to 50,
                130 to 100,
                190 to 100,
                190 to 50,
                296 to 50,
                296 to 200,
                190 to 200,
                190 to 150,
                130 to 150,
                130 to 200,
                24 to 200,
            )
            block(225 to 140, 245 to 140, 245 to 200, 225 to 200)
            block(80 to 100, 100 to 100, 100 to 200, 80 to 200)
            rotatingBar(160 to 125, 34, 200)
            tee(50, 185)
            cup(272, 185)
        },
        // Two rooms with no way between them but a tunnel.
        hole(9, par = 2) {
            green(rect(24, 40, 150, 210))
            green(rect(170, 40, 296, 210))
            tunnel(110 to 70, 1 to 0, 230 to 70, 0 to 1)
            tee(45, 190)
            cup(270, 190)
        },
    )
