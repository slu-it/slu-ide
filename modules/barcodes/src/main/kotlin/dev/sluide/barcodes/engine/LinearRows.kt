package dev.sluide.barcodes.engine

import com.google.zxing.BinaryBitmap
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.BitArray
import com.google.zxing.oned.CodaBarReader
import com.google.zxing.oned.Code128Reader
import com.google.zxing.oned.Code39Reader
import com.google.zxing.oned.Code93Reader
import com.google.zxing.oned.EAN13Reader
import com.google.zxing.oned.EAN8Reader
import com.google.zxing.oned.ITFReader
import com.google.zxing.oned.OneDReader
import com.google.zxing.oned.UPCAReader
import com.google.zxing.oned.UPCEReader
import kotlin.math.abs
import kotlin.math.floor

/**
 * How high a linear code's bars reach. ZXing reads one row, often near the top or bottom of the bars, so this decodes
 * the rows above and below it, one module apart, within the code's x range and its quiet zone, as long as they give
 * the same text.
 */
internal object LinearRows {
    /** Modules of quiet zone around the bars that the probed rows include. */
    private const val QUIET_MODULES = 10

    /**
     * The top and bottom, in [bitmap] pixels, of the rows through [result] that decode to its text, half a probe step
     * beyond the outermost ones; null when its scan line is not level (a turned code) or no row but its own decodes.
     */
    fun span(
        bitmap: BinaryBitmap,
        result: Result,
        format: CodeFormat,
    ): ClosedFloatingPointRange<Double>? {
        val points = result.resultPoints.orEmpty().filterNotNull()
        if (points.size < 2 || abs(points.first().y - points.last().y) > 0.5f) return null
        val modules = Bounds.linearModules(format, result.rawBytes?.size, result.text.length)
        val module = abs(points.last().x - points.first().x) / modules.between
        val from = floor(points.minOf { it.x } - (modules.before + QUIET_MODULES) * module).toInt().coerceAtLeast(0)
        val to = (points.maxOf { it.x } + (modules.after + QUIET_MODULES) * module).toInt().coerceAtMost(bitmap.width)
        val step = maxOf(1, module.toInt())
        val row = points.first().y.toInt()
        val reader = readerFor(format) ?: return null
        val buffer = BitArray(bitmap.width)

        fun decodes(y: Int): Boolean {
            val full = bitmap.getBlackRow(y, buffer)
            val clipped = BitArray(bitmap.width)
            for (x in from until to) if (full[x]) clipped.set(x)
            return try {
                reader.decodeRow(y, clipped, null).text == result.text
            } catch (_: ReaderException) {
                false
            } catch (_: RuntimeException) {
                false
            }
        }
        var top = row
        while (top - step >= 0 && decodes(top - step)) top -= step
        var bottom = row
        while (bottom + step < bitmap.height && decodes(bottom + step)) bottom += step
        if (top == row && bottom == row) return null
        return (top - step / 2.0).coerceAtLeast(0.0)..(bottom + step / 2.0).coerceAtMost(bitmap.height.toDouble())
    }

    private fun readerFor(format: CodeFormat): OneDReader? =
        when (format) {
            CodeFormat.EAN_13 -> EAN13Reader()
            CodeFormat.UPC_A -> UPCAReader()
            CodeFormat.EAN_8 -> EAN8Reader()
            CodeFormat.UPC_E -> UPCEReader()
            CodeFormat.CODE_128 -> Code128Reader()
            CodeFormat.CODE_39 -> Code39Reader()
            CodeFormat.CODE_93 -> Code93Reader()
            CodeFormat.ITF -> ITFReader()
            CodeFormat.CODABAR -> CodaBarReader()
            else -> null
        }
}
