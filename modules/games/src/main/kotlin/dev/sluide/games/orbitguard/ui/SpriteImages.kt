package dev.sluide.games.orbitguard.ui

import dev.sluide.games.orbitguard.Sprite
import javafx.scene.image.Image
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import kotlin.math.floor

/** A sprite rastered to device pixels: [bits] row by row, [width] x [height] device pixels. */
internal class Raster(
    val width: Int,
    val height: Int,
    val bits: BooleanArray,
) {
    fun isSet(
        x: Int,
        y: Int,
    ): Boolean = bits[y * width + x]
}

/**
 * [sprite] at [deviceScale] device pixels per field pixel by nearest neighbour: field pixel i covers the device pixels
 * from floor(i · scale) up to, excluding, floor((i + 1) · scale), the same edges as the snapped field coordinates.
 */
internal fun raster(
    sprite: Sprite,
    deviceScale: Double,
): Raster {
    fun edge(i: Int) = floor(i * deviceScale).toInt()
    val width = edge(sprite.width)
    val height = edge(sprite.height)
    val bits = BooleanArray(width * height)
    for (fy in 0 until sprite.height) {
        for (fx in 0 until sprite.width) {
            if (!sprite.isSet(fx, fy)) continue
            for (dy in edge(fy) until edge(fy + 1)) {
                for (dx in edge(fx) until edge(fx + 1)) bits[dy * width + dx] = true
            }
        }
    }
    return Raster(width, height, bits)
}

/**
 * Sprite images at one [deviceScale] (the field's scale times the output scale), one per sprite and colour, cached.
 * An image is shown at its pixel size divided by the output scale. FX thread.
 */
internal class SpriteImages(
    val deviceScale: Double,
) {
    private val cache = HashMap<Pair<Sprite, Color>, Image>()

    fun image(
        sprite: Sprite,
        color: Color,
    ): Image =
        cache.getOrPut(sprite to color) {
            val raster = raster(sprite, deviceScale)
            val image = WritableImage(maxOf(1, raster.width), maxOf(1, raster.height))
            val argb = argb(color)
            val writer = image.pixelWriter
            for (y in 0 until raster.height) {
                for (x in 0 until raster.width) {
                    if (raster.isSet(x, y)) writer.setArgb(x, y, argb)
                }
            }
            image
        }

    private fun argb(color: Color): Int {
        fun c(v: Double) = (v * 255).toInt() and 0xff
        return (0xff shl 24) or (c(color.red) shl 16) or (c(color.green) shl 8) or c(color.blue)
    }
}
