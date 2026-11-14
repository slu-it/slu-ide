package dev.sluide.barcodes.engine

import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import kotlin.math.roundToInt

/**
 * The image the reader looks at: composited over white, so that codes on a transparent background read, and scaled
 * down by area averaging when its longer side is over the limit. Only the luminance is kept, a byte per pixel;
 * [toOriginal] maps its boxes back.
 */
internal class OpaqueSample private constructor(
    val luminance: LuminanceSource,
    private val originalWidth: Int,
    private val originalHeight: Int,
) {
    val width get() = luminance.width
    val height get() = luminance.height

    /** [box], in the pixels of this sample, in the pixels of the original image. */
    fun toOriginal(box: Box): Box {
        val sx = originalWidth.toDouble() / width
        val sy = originalHeight.toDouble() / height
        return Box(box.x * sx, box.y * sy, box.width * sx, box.height * sy)
    }

    companion object {
        fun of(
            image: ArgbImage,
            maxSide: Int,
        ): OpaqueSample {
            val longer = maxOf(image.width, image.height)
            // RGBLuminanceSource keeps only its own luminance bytes, so the RGB array is garbage once it exists.
            if (longer <= maxSide) {
                val rgb = IntArray(image.argb.size) { overWhite(image.argb[it]) }
                return OpaqueSample(RGBLuminanceSource(image.width, image.height, rgb), image.width, image.height)
            }
            val factor = maxSide.toDouble() / longer
            val width = (image.width * factor).roundToInt().coerceIn(1, maxSide)
            val height = (image.height * factor).roundToInt().coerceIn(1, maxSide)
            val rgb = areaAverage(image, width, height)
            return OpaqueSample(RGBLuminanceSource(width, height, rgb), image.width, image.height)
        }

        private fun areaAverage(
            image: ArgbImage,
            width: Int,
            height: Int,
        ): IntArray {
            // Sample column x covers original columns [starts[x], starts[x + 1]); rows alike.
            val columnStarts = IntArray(width + 1) { (it.toLong() * image.width / width).toInt() }
            val rowStarts = IntArray(height + 1) { (it.toLong() * image.height / height).toInt() }
            val out = IntArray(width * height)
            val red = LongArray(width)
            val green = LongArray(width)
            val blue = LongArray(width)
            for (y in 0 until height) {
                red.fill(0)
                green.fill(0)
                blue.fill(0)
                for (sourceY in rowStarts[y] until rowStarts[y + 1]) {
                    val rowOffset = sourceY * image.width
                    for (x in 0 until width) {
                        for (sourceX in columnStarts[x] until columnStarts[x + 1]) {
                            val pixel = overWhite(image.argb[rowOffset + sourceX])
                            red[x] += (pixel shr 16) and 0xFF
                            green[x] += (pixel shr 8) and 0xFF
                            blue[x] += pixel and 0xFF
                        }
                    }
                }
                val rows = rowStarts[y + 1] - rowStarts[y]
                for (x in 0 until width) {
                    val count = (rows * (columnStarts[x + 1] - columnStarts[x])).coerceAtLeast(1)
                    val r = (red[x] / count).toInt()
                    val g = (green[x] / count).toInt()
                    val b = (blue[x] / count).toInt()
                    out[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return out
        }

        /** [argb] composited over opaque white. */
        internal fun overWhite(argb: Int): Int {
            val alpha = argb ushr 24
            if (alpha == 0xFF) return argb

            fun channel(shift: Int) = (((argb shr shift) and 0xFF) * alpha + 0xFF * (0xFF - alpha)) / 0xFF
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
