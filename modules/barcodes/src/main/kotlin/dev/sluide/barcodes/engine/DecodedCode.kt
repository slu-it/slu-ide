package dev.sluide.barcodes.engine

import com.google.zxing.ResultPoint
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import kotlin.math.hypot

/** A code found in an image: [bounds] covers the whole symbol, in the pixels of the image that was read. */
internal data class DecodedCode(
    val format: CodeFormat,
    val text: String,
    val bounds: Box,
)

/** An axis-aligned rectangle in image pixels. */
internal data class Box(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val right get() = x + width
    val bottom get() = y + height

    fun intersects(other: Box) = x <= other.right && other.x <= right && y <= other.bottom && other.y <= bottom

    internal companion object {
        fun around(points: List<Pair<Double, Double>>): Box {
            val xs = points.map { it.first }
            val ys = points.map { it.second }
            return Box(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
        }
    }
}

/**
 * Turns ZXing's result points into the box of the whole symbol. The points are not its corners: QR gives the centres
 * of its finder patterns (3.5 modules inside the edges), Data Matrix the centres of its corner modules, linear codes
 * two points on one scan line (mostly the centres of the start and stop patterns; the height of their bars comes from
 * [LinearRows]), PDF417 the corners of its start and stop patterns, Aztec the corners of the symbol.
 */
internal object Bounds {
    /** Without probed rows, a linear code's box is as high as this part of its width, centred on the scan line. */
    const val LINEAR_HEIGHT = 0.4

    /**
     * The box of a code of [format] from its result [points], clamped to an image of [imageWidth] × [imageHeight];
     * [rawCodewords] and [ecLevel] are what ZXing decoded (QR's version, the length of a Code 128), [textLength]
     * the length of the decoded text, [rows] the top and bottom of a linear code's bars where they were probed. Null
     * without points.
     */
    fun of(
        format: CodeFormat,
        points: List<ResultPoint>,
        rawCodewords: Int?,
        ecLevel: String?,
        textLength: Int,
        rows: ClosedFloatingPointRange<Double>?,
        imageWidth: Int,
        imageHeight: Int,
    ): Box? {
        if (points.isEmpty()) return null
        val hull = Box.around(points.map { it.x.toDouble() to it.y.toDouble() })
        val box =
            when {
                format.is1D -> {
                    val line = linear(points.first(), points.last(), linearModules(format, rawCodewords, textLength))
                    rows?.let { Box(line.x, it.start, line.width, it.endInclusive - it.start) } ?: line
                }

                format == CodeFormat.QR_CODE && points.size >= 3 -> {
                    qr(points, qrDimension(rawCodewords, ecLevel))
                }

                // Corner module centres: half a module more on each side. The smallest symbol, 10×10, has its corner
                // centres 9 modules apart, so 1/18 of the longer side is at least half a module for every symbol.
                format == CodeFormat.DATA_MATRIX -> {
                    hull.padded(maxOf(hull.width, hull.height) / 18)
                }

                else -> {
                    hull
                }
            }
        val left = box.x.coerceIn(0.0, imageWidth.toDouble())
        val top = box.y.coerceIn(0.0, imageHeight.toDouble())
        val right = box.right.coerceIn(0.0, imageWidth.toDouble())
        val bottom = box.bottom.coerceIn(0.0, imageHeight.toDouble())
        return Box(left, top, right - left, bottom - top)
    }

    /**
     * The QR version's side in modules: the version whose data codewords at [ecLevel] are [dataCodewords] (ZXing's raw
     * bytes are exactly those), or 21 (version 1) when unknown, which pads the most.
     */
    internal fun qrDimension(
        dataCodewords: Int?,
        ecLevel: String?,
    ): Int {
        val level = ecLevel?.let { runCatching { ErrorCorrectionLevel.valueOf(it) }.getOrNull() }
        if (dataCodewords == null || level == null) return 21
        val version =
            (1..40).map { Version.getVersionForNumber(it) }.firstOrNull {
                it.totalCodewords - it.getECBlocksForLevel(level).totalECCodewords == dataCodewords
            }
        return version?.dimensionForVersion ?: 21
    }

    /**
     * The symbol around QR finder centres bottom-left, top-left, top-right ([points] in ZXing's order): the centres are
     * dimension − 7 modules apart and 3.5 modules inside the edges, the fourth corner completes the parallelogram.
     */
    private fun qr(
        points: List<ResultPoint>,
        dimension: Int,
    ): Box {
        val (bottomLeft, topLeft, topRight) = points
        val span = (dimension - 7).toDouble()
        val ax = (topRight.x - topLeft.x) / span
        val ay = (topRight.y - topLeft.y) / span
        val bx = (bottomLeft.x - topLeft.x) / span
        val by = (bottomLeft.y - topLeft.y) / span
        val corners =
            listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1).map { (u, v) ->
                val baseX =
                    topLeft.x + (if (u > 0) topRight.x - topLeft.x else 0f) +
                        (if (v > 0) bottomLeft.x - topLeft.x else 0f)
                val baseY =
                    topLeft.y + (if (u > 0) topRight.y - topLeft.y else 0f) +
                        (if (v > 0) bottomLeft.y - topLeft.y else 0f)
                (baseX + 3.5 * (u * ax + v * bx)) to (baseY + 3.5 * (u * ay + v * by))
            }
        return Box.around(corners)
    }

    /** Where a linear code's two points lie in it: modules [before] the first, [after] the last, [between] them. */
    internal data class LinearModules(
        val before: Double,
        val between: Double,
        val after: Double,
    )

    /**
     * The modules around a linear code's points. EAN and UPC give the centres of their guard patterns, Code 128 the
     * centres of its start pattern and of the first 11 modules of its stop pattern (11 modules per codeword ZXing read,
     * the stop included, plus the stop's final 2). Code 39 and Code 93 give the centres of their start and stop
     * characters; their width in modules is estimated from the text length with a wide-to-narrow ratio of 2, as is
     * ITF's, whose points are the inner ends of its start and end patterns. Codabar gives the ends of its bars.
     */
    internal fun linearModules(
        format: CodeFormat,
        rawCodewords: Int?,
        textLength: Int,
    ): LinearModules =
        when (format) {
            CodeFormat.EAN_13, CodeFormat.UPC_A -> LinearModules(1.5, 92.0, 1.5)
            CodeFormat.EAN_8 -> LinearModules(1.5, 64.0, 1.5)
            CodeFormat.UPC_E -> LinearModules(1.5, 46.5, 3.0)
            CodeFormat.CODE_128 -> LinearModules(5.5, 11.0 * ((rawCodewords ?: 3) - 1).coerceAtLeast(1), 7.5)
            CodeFormat.CODE_39 -> LinearModules(6.5, 13.0 * (textLength + 1), 6.5)
            CodeFormat.CODE_93 -> LinearModules(4.5, 9.0 * (textLength + 3), 5.5)
            CodeFormat.ITF -> LinearModules(4.0, 9.0 * textLength, 5.0)
            else -> LinearModules(0.0, 1.0, 0.0)
        }

    /**
     * The box of a linear code read along the line from [first] to [last]: the line extended by the modules around it,
     * and [LINEAR_HEIGHT] times that length thick, across it. ZXing reads rotated codes too, so the line may be upright.
     */
    private fun linear(
        first: ResultPoint,
        last: ResultPoint,
        modules: LinearModules,
    ): Box {
        val dx = (last.x - first.x).toDouble()
        val dy = (last.y - first.y).toDouble()
        val length = hypot(dx, dy)
        if (length == 0.0) return Box(first.x.toDouble(), first.y.toDouble(), 0.0, 0.0)
        val module = length / modules.between
        val ux = dx / length
        val uy = dy / length
        val startX = first.x - ux * modules.before * module
        val startY = first.y - uy * modules.before * module
        val endX = last.x + ux * modules.after * module
        val endY = last.y + uy * modules.after * module
        val half = (length + (modules.before + modules.after) * module) * LINEAR_HEIGHT / 2
        val corners =
            listOf(startX to startY, endX to endY).flatMap { (x, y) ->
                listOf((x - uy * half) to (y + ux * half), (x + uy * half) to (y - ux * half))
            }
        return Box.around(corners)
    }

    private fun Box.padded(by: Double) = Box(x - by, y - by, width + 2 * by, height + 2 * by)
}
