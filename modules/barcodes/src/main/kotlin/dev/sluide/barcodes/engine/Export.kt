package dev.sluide.barcodes.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Draws a generated code as an image. Every module is a square of a whole number of pixels, so the edges stay crisp:
 * the scale is `sizePx / (modules + 2 × quiet zone)` along the longer side, rounded down and at least 1, so the image
 * can be somewhat smaller than `sizePx` (and larger for a tiny `sizePx`). Linear codes get the quiet zone left and
 * right only and bars 0.4 times the width high (rounded to whole modules).
 */
internal object Export {
    /** The ARGB pixels of [code]; throws IllegalArgumentException for an image over 16 384 px per side. */
    fun argb(
        code: Generated.Code,
        o: GenerateOptions,
    ): ArgbImage {
        val layout = Layout.of(code, o)
        val pixels = IntArray(layout.widthPx * layout.heightPx) { o.background }
        for (y in 0 until layout.heightPx) {
            val row = if (code.format.is1D) 0 else y / layout.scale - layout.quietY
            if (row !in 0 until code.grid.height) continue
            for (x in 0 until layout.widthPx) {
                val column = x / layout.scale - layout.quietX
                if (column in 0 until code.grid.width &&
                    code.grid[column, row]
                ) {
                    pixels[y * layout.widthPx + x] = o.foreground
                }
            }
        }
        return ArgbImage(layout.widthPx, layout.heightPx, pixels)
    }

    /** [code] as PNG bytes; throws IllegalArgumentException for an image over 16 384 px per side. */
    fun png(
        code: Generated.Code,
        o: GenerateOptions,
    ): ByteArray {
        val image = argb(code, o)
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(buffered, "png", out)) throw IOException("No PNG writer is available.")
        return out.toByteArray()
    }

    /**
     * An SVG whose user units are modules (quiet zone included) and whose width and height are the pixels of [png]; the
     * dark modules are one path of horizontal runs. Throws IllegalArgumentException for an image over 16 384 px per side.
     */
    fun svg(
        code: Generated.Code,
        o: GenerateOptions,
    ): String {
        val layout = Layout.of(code, o)
        val path = StringBuilder()
        for (row in 0 until code.grid.height) {
            var x = 0
            while (x < code.grid.width) {
                if (!code.grid[x, row]) {
                    x++
                    continue
                }
                val start = x
                while (x < code.grid.width && code.grid[x, row]) x++
                val top = if (code.format.is1D) 0 else row + layout.quietY
                val height = if (code.format.is1D) layout.heightModules else 1
                path.append("M${start + layout.quietX} ${top}h${x - start}v${height}h-${x - start}z")
            }
        }
        val w = layout.widthModules
        val h = layout.heightModules
        return buildString {
            append(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${layout.widthPx}\" height=\"${layout.heightPx}\" ",
            )
            append("viewBox=\"0 0 $w $h\" shape-rendering=\"crispEdges\">\n")
            append("<rect width=\"$w\" height=\"$h\"${fill(o.background)}/>\n")
            if (path.isNotEmpty()) append("<path d=\"$path\"${fill(o.foreground)}/>\n")
            append("</svg>\n")
        }
    }

    private fun fill(argb: Int): String {
        val alpha = argb ushr 24
        val color = " fill=\"#%06x\"".format(Locale.ROOT, argb and 0xFFFFFF)
        return if (alpha == 255) color else color + " fill-opacity=\"%.3f\"".format(Locale.ROOT, alpha / 255.0)
    }
}

/** Where the modules go: [scale] pixels per module, quiet zones and the size in modules and in pixels. */
internal data class Layout(
    val scale: Int,
    val quietX: Int,
    val quietY: Int,
    val widthModules: Int,
    val heightModules: Int,
) {
    val widthPx get() = widthModules * scale
    val heightPx get() = heightModules * scale

    init {
        require(widthPx <= MAX_PX && heightPx <= MAX_PX) { "Image $widthPx×$heightPx is over $MAX_PX px per side" }
    }

    companion object {
        /** No exported image is larger per side; the generator already refuses codes over 4096 modules. */
        const val MAX_PX = 16_384

        fun of(
            code: Generated.Code,
            o: GenerateOptions,
        ): Layout {
            val quiet = o.quietZoneFor(code.format).coerceAtLeast(0)
            val widthModules = code.grid.width + 2 * quiet
            if (code.format.is1D) {
                val heightModules = (0.4 * widthModules).roundToInt().coerceAtLeast(1)
                return Layout((o.sizePx / widthModules).coerceAtLeast(1), quiet, 0, widthModules, heightModules)
            }
            val heightModules = code.grid.height + 2 * quiet
            val scale = (o.sizePx / maxOf(widthModules, heightModules)).coerceAtLeast(1)
            return Layout(scale, quiet, quiet, widthModules, heightModules)
        }
    }
}
