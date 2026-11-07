package dev.sluide.games.orbitguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class SpritesTest {
    private fun assertSize(
        sprite: Sprite,
        width: Int,
        height: Int,
    ) {
        assertEquals(width, sprite.width)
        assertEquals(height, sprite.height)
        assertEquals(height, sprite.rows.size)
        assertEquals(setOf(width), sprite.rows.map { it.length }.toSet())
        assertEquals(setOf('#', '.'), sprite.rows.joinToString("").toSet())
    }

    @Test
    fun `the sprites have their sizes`() {
        assertSize(Sprites.SHIP, 13, 8)
        assertSize(Sprites.BONUS, 16, 7)
        assertSize(Sprites.SHIELD, 24, 16)
        assertSize(Sprites.ENEMY_EXPLOSION, 12, 8)
        assertSize(Sprites.SHIP_EXPLOSION, 15, 8)
        assertSize(Sprites.SHOT_EXPLOSION, 6, 6)
    }

    @ParameterizedTest
    @CsvSource("SCOUT,9,8", "RAIDER,13,8", "BRUTE,14,7")
    fun `each enemy kind has two frames of its size`(
        kind: EnemyKind,
        width: Int,
        height: Int,
    ) {
        assertSize(Sprites.enemy(kind, 0), width, height)
        assertSize(Sprites.enemy(kind, 1), width, height)
        assertNotEquals(Sprites.enemy(kind, 0).rows, Sprites.enemy(kind, 1).rows)
    }
}
