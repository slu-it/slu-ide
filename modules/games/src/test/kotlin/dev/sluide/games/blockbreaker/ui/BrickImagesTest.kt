package dev.sluide.games.blockbreaker.ui

import dev.sluide.games.blockbreaker.BrickKind
import dev.sluide.games.blockbreaker.PowerUp
import dev.sluide.games.blockbreaker.Rules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrickImagesTest {
    @Test
    fun `scaling uses the snapped edges of the device pixels`() {
        val bitmap = Bitmap(2, 1)
        bitmap.set(0, 0, 1)
        bitmap.set(1, 0, 2)

        val result = scaled(bitmap, 1.5)

        // Field pixel 0 covers device pixel 0, pixel 1 covers 1 and 2 (floor(1.5) to floor(3)).
        assertEquals(3, result.width)
        assertEquals(listOf(1, 2, 2), (0 until 3).map { result.get(it, 0) })
    }

    @Test
    fun `a damaged brick differs from an intact one and more damage shows more`() {
        val intact = BrickBitmaps.brick(BrickKind.TOUGH, 0, 0)
        val cracked = BrickBitmaps.brick(BrickKind.TOUGH, 0, 1)
        val hardOne = BrickBitmaps.brick(BrickKind.HARD, 0, 1)
        val hardTwo = BrickBitmaps.brick(BrickKind.HARD, 0, 2)

        assertNotEquals(intact.argb.toList(), cracked.argb.toList())
        assertNotEquals(hardOne.argb.toList(), hardTwo.argb.toList())
        assertEquals(Rules.BRICK_WIDTH, intact.width)
        assertEquals(Rules.BRICK_HEIGHT, intact.height)
    }

    @Test
    fun `the five normal tiers have their own colours`() {
        val colours = (1..5).map { BrickBitmaps.baseColor(BrickKind.NORMAL, it) }

        assertEquals(5, colours.toSet().size)
    }

    @Test
    fun `a brick has a light top and a dark bottom edge`() {
        val brick = BrickBitmaps.brick(BrickKind.NORMAL, 1, 0)

        fun sum(argb: Int) = ((argb shr 16) and 0xff) + ((argb shr 8) and 0xff) + (argb and 0xff)

        assertTrue(sum(brick.get(5, 0)) > sum(brick.get(5, 3)))
        assertTrue(sum(brick.get(5, 3)) > sum(brick.get(5, Rules.BRICK_HEIGHT - 1)))
    }

    @Test
    fun `each capsule kind has its own picture with a letter`() {
        val bitmaps = PowerUp.entries.map { BrickBitmaps.capsule(it) }

        assertEquals(4, bitmaps.map { it.argb.toList() }.toSet().size)
        assertTrue(bitmaps.all { bitmap -> bitmap.argb.count { it == 0xffffffff.toInt() } >= 5 })
    }

    @Test
    fun `the letter of a capsule is centred`() {
        for (kind in PowerUp.entries) {
            val bitmap = BrickBitmaps.capsule(kind)
            val white = 0xffffffff.toInt()
            val xs = (0 until bitmap.width).filter { x -> (0 until bitmap.height).any { bitmap.get(x, it) == white } }
            val ys = (0 until bitmap.height).filter { y -> (0 until bitmap.width).any { bitmap.get(it, y) == white } }

            assertEquals(bitmap.width - 1, xs.min() + xs.max(), "$kind horizontally")
            assertEquals(bitmap.height - 1, ys.min() + ys.max(), "$kind vertically")
        }
    }

    @Test
    fun `the paddle has rounded ends`() {
        val paddle = BrickBitmaps.paddle(40)

        assertEquals(0, paddle.get(0, 0))
        assertEquals(0, paddle.get(39, Rules.PADDLE_HEIGHT - 1))
        assertNotEquals(0, paddle.get(20, 0))
    }
}
