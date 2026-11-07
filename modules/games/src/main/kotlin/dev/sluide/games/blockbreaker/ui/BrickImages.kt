package dev.sluide.games.blockbreaker.ui

import dev.sluide.games.blockbreaker.BrickKind
import dev.sluide.games.blockbreaker.PowerUp
import dev.sluide.games.blockbreaker.Rules
import javafx.scene.image.Image
import javafx.scene.image.WritableImage
import kotlin.math.floor

/** A picture in logical field pixels: [argb] row by row, 0 for transparent. */
internal class Bitmap(
    val width: Int,
    val height: Int,
    val argb: IntArray = IntArray(width * height),
) {
    fun set(
        x: Int,
        y: Int,
        color: Int,
    ) {
        if (x in 0 until width && y in 0 until height) argb[y * width + x] = color
    }

    fun get(
        x: Int,
        y: Int,
    ): Int = argb[y * width + x]

    fun fill(color: Int) = argb.fill(color)

    fun row(
        y: Int,
        x0: Int,
        x1: Int,
        color: Int,
    ) = (x0..x1).forEach { set(it, y, color) }

    fun column(
        x: Int,
        y0: Int,
        y1: Int,
        color: Int,
    ) = (y0..y1).forEach { set(x, it, color) }
}

/** [bitmap] at [deviceScale] device pixels per field pixel by nearest neighbour, the same edges as the snapped rects. */
internal fun scaled(
    bitmap: Bitmap,
    deviceScale: Double,
): Bitmap {
    fun edge(i: Int) = floor(i * deviceScale).toInt()
    val result = Bitmap(edge(bitmap.width), edge(bitmap.height))
    for (fy in 0 until bitmap.height) {
        for (fx in 0 until bitmap.width) {
            val color = bitmap.get(fx, fy)
            if (color == 0) continue
            for (dy in edge(fy) until edge(fy + 1)) {
                for (dx in edge(fx) until edge(fx + 1)) result.set(dx, dy, color)
            }
        }
    }
    return result
}

/** The pictures of the bricks, the paddle and the capsules. Pure. */
internal object BrickBitmaps {
    private val NORMAL_TIERS = intArrayOf(0xe5484d, 0xf08a24, 0xe9c63a, 0x46b96b, 0x4a8fe0)
    private const val TOUGH = 0x9b6fd6
    private const val HARD = 0x2fa6a0
    private const val STEEL = 0x8b95a3
    private const val CRACK = 0x1c1f2b
    private const val PADDLE = 0x7fb2ff
    private const val WHITE = 0xffffff

    private val GLYPHS =
        mapOf(
            PowerUp.WIDE to listOf("X....X", "X.XX.X", "X.XX.X", ".X..X."),
            PowerUp.MULTI to listOf("X....X", "XX..XX", "X.XX.X", "X....X"),
            PowerUp.SLOW to listOf(".XXXXX", "XX....", "....XX", "XXXXX."),
            PowerUp.LIFE to listOf("X.....", "X.....", "X.....", "XXXXXX"),
        )

    private fun opaque(rgb: Int) = (0xff shl 24) or rgb

    /** [rgb] lightened by [factor] towards white, or darkened towards black when negative; opaque. */
    private fun shade(
        rgb: Int,
        factor: Double,
    ): Int {
        fun c(shift: Int): Int {
            val v = (rgb shr shift) and 0xff
            val result = if (factor >= 0) v + ((255 - v) * factor).toInt() else v + (v * factor).toInt()
            return result.coerceIn(0, 255)
        }
        return opaque((c(16) shl 16) or (c(8) shl 8) or c(0))
    }

    /** The colour of a brick of [kind] and [tier] (tiers 1 to 5 matter for NORMAL only). */
    fun baseColor(
        kind: BrickKind,
        tier: Int,
    ): Int =
        when (kind) {
            BrickKind.NORMAL -> NORMAL_TIERS[(tier - 1).coerceIn(0, NORMAL_TIERS.size - 1)]
            BrickKind.TOUGH -> TOUGH
            BrickKind.HARD -> HARD
            BrickKind.STEEL -> STEEL
        }

    /** A brick with a light top and left edge, a dark bottom and right edge, and [damage] crack lines. */
    fun brick(
        kind: BrickKind,
        tier: Int,
        damage: Int,
    ): Bitmap {
        val w = Rules.BRICK_WIDTH
        val h = Rules.BRICK_HEIGHT
        val base = baseColor(kind, tier)
        val bitmap = Bitmap(w, h)
        bitmap.fill(opaque(base))
        val light = shade(base, 0.45)
        val dark = shade(base, -0.45)
        bitmap.row(0, 0, w - 1, light)
        bitmap.column(0, 0, h - 1, light)
        bitmap.row(h - 1, 0, w - 1, dark)
        bitmap.column(w - 1, 1, h - 1, dark)
        if (kind == BrickKind.STEEL) bitmap.row(2, 3, 9, shade(base, 0.8))
        if (damage >= 1) crack(bitmap, 8)
        if (damage >= 2) crack(bitmap, 17)
        return bitmap
    }

    private fun crack(
        bitmap: Bitmap,
        x: Int,
    ) {
        for (y in 1 until Rules.BRICK_HEIGHT - 1) {
            bitmap.set(x + (if (y % 2 == 0) 1 else 0), y, opaque(CRACK))
        }
    }

    /** The paddle: a rounded bar of [width] field pixels with a highlight. */
    fun paddle(width: Int): Bitmap {
        val h = Rules.PADDLE_HEIGHT
        val bitmap = Bitmap(width, h)
        bitmap.fill(opaque(PADDLE))
        bitmap.row(1, 1, width - 2, shade(PADDLE, 0.6))
        bitmap.row(h - 1, 0, width - 1, shade(PADDLE, -0.4))
        for (x in listOf(0, width - 1)) {
            bitmap.set(x, 0, 0)
            bitmap.set(x, h - 1, 0)
        }
        return bitmap
    }

    /** The colour of the capsule of [kind]. */
    fun capsuleColor(kind: PowerUp): Int =
        when (kind) {
            PowerUp.WIDE -> 0x3d7be0
            PowerUp.MULTI -> 0xe0873d
            PowerUp.SLOW -> 0x3dae6a
            PowerUp.LIFE -> 0xd6455f
        }

    /** A capsule with the letter W, M, S or L. */
    fun capsule(kind: PowerUp): Bitmap {
        val w = Rules.CAPSULE_WIDTH
        val h = Rules.CAPSULE_HEIGHT
        val base = capsuleColor(kind)
        val bitmap = Bitmap(w, h)
        bitmap.fill(opaque(base))
        bitmap.row(0, 1, w - 2, shade(base, 0.5))
        bitmap.row(h - 1, 1, w - 2, shade(base, -0.5))
        for (x in listOf(0, w - 1)) {
            bitmap.set(x, 0, 0)
            bitmap.set(x, h - 1, 0)
        }
        for ((dy, line) in GLYPHS.getValue(kind).withIndex()) {
            for ((dx, c) in line.withIndex()) {
                if (c == 'X') bitmap.set(3 + dx, 1 + dy, opaque(WHITE))
            }
        }
        return bitmap
    }
}

/**
 * The brick, paddle and capsule images at one [deviceScale] (the field's scale times the output scale), cached. An
 * image is drawn stretched into the snapped rect of its field size (see Frame.image). FX thread.
 */
internal class BrickImages(
    val deviceScale: Double,
) {
    // Indexed by kind, colour tier (0 to 5) and damage (0 to 2): no key objects per frame.
    private val bricks = arrayOfNulls<Image>(BrickKind.entries.size * TIERS * DAMAGES)
    private val capsules = arrayOfNulls<Image>(PowerUp.entries.size)
    private var paddleWidth = 0
    private var paddleImage: Image? = null

    fun brick(
        kind: BrickKind,
        tier: Int,
        hitsLeft: Int,
    ): Image {
        val damage = if (kind.hits == 0) 0 else (kind.hits - hitsLeft).coerceIn(0, kind.hits - 1)
        val colourTier = if (kind == BrickKind.NORMAL) tier.coerceIn(0, TIERS - 1) else 0
        val index = (kind.ordinal * TIERS + colourTier) * DAMAGES + damage
        return bricks[index] ?: image(BrickBitmaps.brick(kind, tier, damage)).also { bricks[index] = it }
    }

    /** The paddle image of [width]; one at a time is kept, since the width only changes with WIDE. */
    fun paddle(width: Int): Image {
        paddleImage?.takeIf { paddleWidth == width }?.let { return it }
        return image(BrickBitmaps.paddle(width)).also {
            paddleImage = it
            paddleWidth = width
        }
    }

    fun capsule(kind: PowerUp): Image =
        capsules[kind.ordinal] ?: image(BrickBitmaps.capsule(kind)).also { capsules[kind.ordinal] = it }

    private companion object {
        const val TIERS = 6
        const val DAMAGES = 3
    }

    private fun image(bitmap: Bitmap): Image {
        val device = scaled(bitmap, deviceScale)
        val image = WritableImage(maxOf(1, device.width), maxOf(1, device.height))
        val writer = image.pixelWriter
        for (y in 0 until device.height) {
            for (x in 0 until device.width) writer.setArgb(x, y, device.get(x, y))
        }
        return image
    }
}
