package dev.sluide.games.orbitguard.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.orbitguard.EnemyKind
import dev.sluide.games.orbitguard.ExplosionKind
import dev.sluide.games.orbitguard.OrbitGuardGame
import dev.sluide.games.orbitguard.Shield
import dev.sluide.games.orbitguard.Sprite
import dev.sluide.games.orbitguard.Sprites
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import kotlin.random.Random

/** The colours of the field, our own in both themes. */
internal object Palette {
    // Keep in sync with .og-board in orbit-guard.css.
    val BACKGROUND: Color = Color.web("#070a14")
    val SHIP: Color = Color.web("#5fd7ff")
    val SCOUT: Color = Color.web("#ff5fd2")
    val RAIDER: Color = Color.web("#ffb347")
    val BRUTE: Color = Color.web("#a6e22e")
    val BONUS: Color = Color.web("#ff6b6b")
    val SHIELD: Color = Color.web("#6c8cbf")
    val PLAYER_SHOT: Color = Color.web("#ffffff")
    val ENEMY_SHOT: Color = Color.web("#ff9d3b")
    val HUD: Color = Color.web("#c8d3f5")
    val GROUND: Color = Color.web("#3a4a6b")

    fun of(kind: EnemyKind): Color =
        when (kind) {
            EnemyKind.SCOUT -> SCOUT
            EnemyKind.RAIDER -> RAIDER
            EnemyKind.BRUTE -> BRUTE
        }
}

/** What a painter needs to draw one frame: the context, the field's placement and the sprite images. */
internal class Frame(
    val gc: GraphicsContext,
    val place: FieldPlacement,
    val images: SpriteImages,
    val small: SpriteImages,
) {
    fun rect(
        color: Color,
        fx: Double,
        fy: Double,
        fw: Double,
        fh: Double,
    ) {
        gc.fill = color
        gc.fillRect(place.x(fx), place.y(fy), place.x(fx + fw) - place.x(fx), place.y(fy + fh) - place.y(fy))
    }

    fun sprite(
        sprite: Sprite,
        color: Color,
        fx: Double,
        fy: Double,
        smaller: Boolean = false,
    ) {
        val image = (if (smaller) small else images).image(sprite, color)
        gc.drawImage(image, place.x(fx), place.y(fy), image.width / place.outputScale, image.height / place.outputScale)
    }
}

/** Draws the stars and everything of the game that lives on the field. */
internal class FieldPainter {
    private class Star(
        val x: Int,
        val y: Int,
        val color: Color,
    )

    private val stars =
        Random(STAR_SEED).let { random ->
            List(STAR_COUNT) {
                val grey = 0.25 + random.nextDouble() * 0.35
                Star(random.nextInt(OrbitGuardGame.WIDTH), random.nextInt(GROUND_TOP), Color.gray(grey))
            }
        }

    fun paint(
        f: Frame,
        game: OrbitGuardGame,
    ) {
        stars.forEach { f.rect(it.color, it.x.toDouble(), it.y.toDouble(), 1.0, 1.0) }
        f.rect(Palette.GROUND, 0.0, GROUND_Y, OrbitGuardGame.WIDTH.toDouble(), 1.0)
        game.shields.forEach { shield(f, it) }
        game.enemies.forEach { f.sprite(Sprites.enemy(it.kind, game.animationFrame), Palette.of(it.kind), it.x, it.y) }
        game.bonus?.let { f.sprite(Sprites.BONUS, Palette.BONUS, it.x, it.y) }
        game.playerShots.forEach { f.rect(Palette.PLAYER_SHOT, it.x, it.y, SHOT_W, SHOT_H) }
        game.enemyShots.forEach { f.rect(Palette.ENEMY_SHOT, it.x, it.y, SHOT_W, SHOT_H) }
        // The ship is gone while its explosion plays.
        if (game.explosions.none { it.kind == ExplosionKind.SHIP }) {
            f.sprite(Sprites.SHIP, Palette.SHIP, game.shipX, game.shipY)
        }
    }

    /** The explosions; the points of a bonus explosion are drawn as text by [hud]. */
    fun explosions(
        f: Frame,
        game: OrbitGuardGame,
        hud: HudPainter,
    ) {
        for (e in game.explosions) {
            when (e.kind) {
                ExplosionKind.ENEMY -> f.sprite(Sprites.ENEMY_EXPLOSION, ENEMY_EXPLOSION_COLOR, e.x, e.y)
                ExplosionKind.SHIP -> f.sprite(Sprites.SHIP_EXPLOSION, Palette.SHIP, e.x, e.y)
                ExplosionKind.SHOT -> f.sprite(Sprites.SHOT_EXPLOSION, Palette.PLAYER_SHOT, e.x, e.y)
                ExplosionKind.BONUS -> hud.bonusPoints(f, e.points, e.x + Sprites.ENEMY_EXPLOSION.width / 2.0, e.y)
            }
        }
    }

    private fun shield(
        f: Frame,
        shield: Shield,
    ) {
        for (py in 0 until shield.height) {
            var px = 0
            while (px < shield.width) {
                if (!shield.isSolid(px, py)) {
                    px++
                    continue
                }
                val start = px
                while (px < shield.width && shield.isSolid(px, py)) px++
                val width = (px - start).toDouble()
                f.rect(Palette.SHIELD, (shield.x + start).toDouble(), (shield.y + py).toDouble(), width, 1.0)
            }
        }
    }

    private companion object {
        const val GROUND_Y = 228.0
        const val GROUND_TOP = 226
        const val STAR_COUNT = 70
        const val STAR_SEED = 7L
        const val SHOT_W = 1.0
        const val SHOT_H = 4.0

        // The enemy kind of an explosion is not part of its state; a bright warm tone stands for all three.
        val ENEMY_EXPLOSION_COLOR: Color = Color.web("#ffe9a8")
    }
}
