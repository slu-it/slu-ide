package dev.sluide.games.minigolf

/** Reference solutions of holes 4-9 ([FRONT_HOLES]), found with [ShotSearch]. */
internal val FRONT_SOLUTIONS: Map<Int, List<RefShot>> =
    mapOf(
        4 to listOf(RefShot(0, 5.3669, 0.925)),
        5 to listOf(RefShot(0, 0.7854, 0.9), RefShot(0, 5.6723, 0.875)),
        6 to listOf(RefShot(0, 4.3633, 0.95), RefShot(0, 6.2396, 0.3)),
        7 to listOf(RefShot(0, 5.3669, 0.5), RefShot(0, 1.0036, 0.3), RefShot(0, 0.0, 0.375)),
        8 to listOf(RefShot(0, 4.1015, 0.975), RefShot(0, 1.3526, 0.35)),
        9 to listOf(RefShot(0, 1.2654, 0.85), RefShot(0, 0.0, 0.275)),
    )
