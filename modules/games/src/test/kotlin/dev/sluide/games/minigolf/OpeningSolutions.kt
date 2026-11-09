package dev.sluide.games.minigolf

/** Reference solutions of holes 1-3 ([OPENING_HOLES]), found with [ShotSearch]. */
internal val OPENING_SOLUTIONS: Map<Int, List<RefShot>> =
    mapOf(
        1 to listOf(RefShot(0, 0.1745, 0.45), RefShot(0, 5.236, 0.6)),
        2 to listOf(RefShot(0, 6.1959, 0.975), RefShot(0, 0.0, 0.125)),
        3 to listOf(RefShot(0, 0.5672, 0.875), RefShot(0, 5.716, 0.8)),
    )
