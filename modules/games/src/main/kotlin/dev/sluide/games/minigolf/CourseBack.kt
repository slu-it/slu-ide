package dev.sluide.games.minigolf

/** Holes 10-18 of [COURSE]: combinations of the elements the first nine introduce. */
internal val BACK_HOLES: List<Hole> =
    listOf(
        // 10: a zigzag of diagonal baffles; the gaps alternate between the top and the bottom.
        hole(10, par = 3) {
            green(rect(20, 30, 300, 226))
            wall(110 to 226, 150 to 100)
            wall(190 to 30, 230 to 150)
            wall(250 to 226, 270 to 150)
            tee(40, 200)
            cup(285, 60)
        },
        // 11: a wall with a narrow gap, then a funnel; the slopes steer what comes through toward the cup, and a
        // pond behind the cup takes what is too fast.
        hole(11, par = 2) {
            green(30 to 60, 200 to 60, 290 to 110, 290 to 150, 200 to 200, 30 to 200)
            block(rect(140, 60, 150, 125))
            block(rect(140, 135, 150, 200))
            water(rect(278, 122, 290, 138))
            slope(poly(200 to 60, 290 to 110, 290 to 122, 200 to 122), 0 to 50)
            slope(poly(200 to 200, 290 to 150, 290 to 138, 200 to 138), 0 to -50)
            tee(50, 130)
            cup(262, 130)
        },
        // 12: a block slides across the top lane; the cup lies in the lane below, around a U-turn.
        hole(12, par = 3) {
            green(30 to 70, 290 to 70, 290 to 225, 30 to 225, 30 to 185, 230 to 185, 230 to 160, 30 to 160)
            slidingBlock(140 to 100, 140 to 130, 12, 50, 180)
            tee(50, 115)
            cup(50, 205)
        },
        // 13: the cup on an island of a pond; the only way in is a 16-unit causeway from the north bank.
        hole(13, par = 3) {
            green(rect(30, 50, 290, 210))
            water(rect(150, 90, 198, 200))
            water(rect(198, 185, 280, 200))
            water(rect(264, 90, 280, 185))
            water(rect(214, 90, 264, 140))
            tee(50, 130)
            cup(244, 163)
        },
        // 14: two chambers joined by two tunnels; the upper mouth comes out at the lower right, the lower mouth at the
        // top right, next to the cup.
        hole(14, par = 3) {
            green(rect(20, 40, 150, 220))
            green(rect(170, 40, 300, 220))
            block(rect(215, 90, 255, 140))
            tunnel(120 to 60, -1 to 0, 190 to 190, 1 to -0.4)
            tunnel(120 to 190, -1 to 0, 190 to 60, 0 to 1)
            tee(40, 130)
            cup(275, 65)
        },
        // 15: sand to cross at full power, a slope that drifts the ball down, two walls with gaps that do not line up,
        // and a second slope that leads the ball down into the cup.
        hole(15, par = 3) {
            green(rect(30, 60, 290, 190))
            slope(rect(60, 80, 120, 140), 0 to 55)
            slope(rect(245, 85, 285, 135), 0 to 55)
            sand(rect(130, 60, 180, 190))
            block(rect(195, 60, 205, 125))
            block(rect(195, 135, 205, 190))
            block(rect(225, 60, 235, 80))
            block(rect(225, 90, 235, 190))
            tee(50, 125)
            cup(272, 125)
        },
        // 16: three lanes in a serpentine, a rotating bar in the first and in the last.
        hole(16, par = 4) {
            green(
                20 to 30,
                300 to 30,
                300 to 156,
                55 to 156,
                55 to 170,
                300 to 170,
                300 to 226,
                20 to 226,
                20 to 100,
                265 to 100,
                265 to 86,
                20 to 86,
            )
            block(rect(120, 118, 200, 138))
            rotatingBar(140 to 58, 38, 200)
            rotatingBar(190 to 198, 38, -240)
            tee(40, 58)
            cup(270, 198)
        },
        // 17: a serpentine of three lanes; bumpers in the bottom and the top lane, the cup at the far end.
        hole(17, par = 4) {
            green(rect(20, 30, 300, 230))
            block(rect(20, 90, 250, 120))
            block(rect(70, 150, 300, 180))
            bumper(150, 205, 9)
            bumper(215, 205, 9)
            bumper(230, 60, 10)
            bumper(130, 50, 9)
            tee(270, 205)
            cup(40, 60)
        },
        // 18: a river only the tunnel crosses; beyond it a shelf, a sliding block and a slope guard the cup.
        hole(18, par = 4) {
            green(rect(20, 40, 300, 220))
            water(rect(130, 40, 170, 220))
            block(rect(190, 105, 300, 125))
            tunnel(100 to 60, 0 to 1, 200 to 60, 1 to 0)
            slidingBlock(235 to 150, 235 to 195, 12, 40, 210)
            slope(rect(250, 175, 300, 220), 0 to -50)
            tee(50, 200)
            cup(275, 195)
        },
    )
