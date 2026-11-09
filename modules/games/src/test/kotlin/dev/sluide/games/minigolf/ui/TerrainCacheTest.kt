package dev.sluide.games.minigolf.ui

import dev.sluide.games.minigolf.hole
import dev.sluide.games.minigolf.poly
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TerrainCacheTest {
    private val sand = poly(40 to 40, 100 to 40, 100 to 60, 70 to 60, 70 to 90, 40 to 90)
    private val water = poly(150 to 40, 230 to 40, 230 to 100, 150 to 100)
    private val slope = poly(40 to 120, 120 to 120, 120 to 180, 40 to 180)
    private val cache =
        TerrainCache(
            hole(1, par = 2) {
                green(10 to 20, 310 to 20, 310 to 235, 10 to 235)
                tee(20, 200)
                cup(300, 200)
                sand(sand)
                water(water)
                slope(slope, 50 to 0)
            },
        )

    @Test
    fun `the sand grain lies inside the area`() {
        val grain = cache.sandDark[0] + cache.sandLight[0]

        assertTrue(grain.size > 20)
        assertTrue(grain.all { sand.contains(it) })
    }

    @Test
    fun `ripples and slope arrows keep clear of the edges`() {
        assertTrue(cache.ripples[0].isNotEmpty())
        assertTrue(cache.ripples[0].all { r -> water.edges().all { it.distanceTo(r.center) >= 4.0 } })
        assertTrue(cache.arrows[0].isNotEmpty())
        assertTrue(cache.arrows[0].all { slope.contains(it.from) && slope.contains(it.to) })
    }
}
