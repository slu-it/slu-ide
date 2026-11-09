package dev.sluide.games.minigolf

/** Reference solutions of holes 10-18 ([BACK_HOLES]), found with [ShotSearch]. */
internal val BACK_SOLUTIONS: Map<Int, List<RefShot>> =
    mapOf(
        10 to listOf(RefShot(0, 5.1487, 1.0), RefShot(0, 0.0, 0.875)),
        11 to listOf(RefShot(0, 0.0, 0.575)),
        12 to listOf(RefShot(30, 5.716, 1.0), RefShot(0, 2.8362, 0.25)),
        13 to listOf(RefShot(0, 5.5851, 0.525), RefShot(0, 1.3526, 0.325), RefShot(0, 6.1087, 0.225)),
        14 to listOf(RefShot(0, 5.5851, 0.85), RefShot(0, 4.8869, 0.15)),
        15 to listOf(RefShot(0, 2.5307, 1.0), RefShot(0, 0.4363, 0.65), RefShot(0, 0.4363, 0.75)),
        16 to listOf(RefShot(0, 5.9341, 0.95), RefShot(0, 2.8798, 1.0), RefShot(0, 0.2618, 0.65)),
        17 to
            listOf(
                RefShot(0, 1.7453, 0.9),
                RefShot(0, 2.7053, 0.95),
                RefShot(0, 5.9341, 1.0),
                RefShot(0, 2.9671, 0.65),
            ),
        18 to
            listOf(
                RefShot(0, 1.3963, 1.0),
                RefShot(0, 5.0615, 1.0),
                RefShot(0, 2.5307, 0.2),
                RefShot(105, 1.309, 0.85),
            ),
    )
