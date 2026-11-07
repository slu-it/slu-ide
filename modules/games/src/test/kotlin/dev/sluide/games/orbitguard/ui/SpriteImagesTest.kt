package dev.sluide.games.orbitguard.ui

import dev.sluide.games.orbitguard.Sprite
import dev.sluide.games.orbitguard.Sprites
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

class SpriteImagesTest {
    @Test
    fun `at device scale 1 the raster is the sprite`() {
        val r = raster(Sprites.SHIP, 1.0)

        assertEquals(Sprites.SHIP.width, r.width)
        assertEquals(Sprites.SHIP.height, r.height)
        for (y in 0 until r.height) {
            for (x in 0 until r.width) assertEquals(Sprites.SHIP.isSet(x, y), r.isSet(x, y), "($x, $y)")
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 2.0, 2.5, 3.3])
    fun `a set field pixel covers exactly its snapped device pixels`(scale: Double) {
        val sprite = Sprite(listOf("#.#", ".#.", "#.."))
        val r = raster(sprite, scale)

        assertEquals(floor(3 * scale).toInt(), r.width)
        assertEquals(floor(3 * scale).toInt(), r.height)
        for (fy in 0 until 3) {
            for (fx in 0 until 3) {
                val xs = floor(fx * scale).toInt() until floor((fx + 1) * scale).toInt()
                val ys = floor(fy * scale).toInt() until floor((fy + 1) * scale).toInt()
                for (y in ys) for (x in xs) assertEquals(sprite.isSet(fx, fy), r.isSet(x, y), "($x, $y) of ($fx, $fy)")
            }
        }
    }

    @Test
    fun `an empty sprite pixel stays empty at a fractional scale`() {
        val r = raster(Sprite(listOf("#.")), 2.5)

        assertEquals(5, r.width)
        assertEquals(true, r.isSet(1, 0))
        assertEquals(false, r.isSet(2, 0))
        assertEquals(false, r.isSet(4, 0))
    }
}
