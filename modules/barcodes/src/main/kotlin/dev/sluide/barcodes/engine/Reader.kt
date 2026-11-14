package dev.sluide.barcodes.engine

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.MultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import kotlin.math.abs

/**
 * Finds every code in an image, of all [CodeFormat]s. Slow on large images, so it runs off the FX thread; images with
 * a side over [MAX_SIDE] are scaled down first, so that it cannot take minutes.
 */
internal object Reader {
    const val MAX_SIDE = 4096

    /** Images with a smaller side hold no readable code. */
    private const val MIN_SIDE = 8

    private val HINTS =
        mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to CodeFormat.entries.map { it.barcodeFormat },
        )

    /** The codes in [image], top to bottom by rows, then left to right; empty when there are none. */
    fun read(image: ArgbImage): List<DecodedCode> {
        if (image.width < MIN_SIDE || image.height < MIN_SIDE) return emptyList()
        val sample = OpaqueSample.of(image, MAX_SIDE)
        val source = sample.luminance
        // ZXing's ALSO_INVERTED hint covers neither QRCodeMultiReader nor the rows the linear readers scan, and it
        // flips the shared bit matrix in place: an explicit pass over the inverted image reads light-on-dark codes.
        val found =
            listOf(source, InvertedLuminanceSource(source)).flatMap { decode(it) }.map { (format, result, rows) ->
                val box =
                    Bounds.of(
                        format,
                        result.resultPoints.orEmpty().filterNotNull(),
                        result.rawBytes?.size,
                        result.resultMetadata?.get(ResultMetadataType.ERROR_CORRECTION_LEVEL) as? String,
                        result.text.length,
                        rows,
                        sample.width,
                        sample.height,
                    ) ?: Box(0.0, 0.0, sample.width.toDouble(), sample.height.toDouble())
                DecodedCode(format, result.text, sample.toOriginal(box))
            }
        return inReadingOrder(distinct(found))
    }

    /** A result of a format Thoreau knows, with the rows a linear code's bars reach ([LinearRows]). */
    private data class Found(
        val format: CodeFormat,
        val result: Result,
        val rows: ClosedFloatingPointRange<Double>?,
    )

    /**
     * The whole-image readers, then the tiled pass for Data Matrix and Aztec on the same black matrix; linear codes are
     * probed for the height of their bars on the same binarizer.
     */
    private fun decode(source: LuminanceSource): List<Found> {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val readers: List<MultipleBarcodeReader> =
            listOf(GenericMultipleBarcodeReader(UnrotatingReader()), QRCodeMultiReader())
        val whole =
            readers.flatMap { reader ->
                try {
                    reader.decodeMultiple(bitmap, HINTS).toList()
                } catch (_: ReaderException) {
                    emptyList()
                } catch (_: RuntimeException) {
                    // ZXing's detectors can fail with index errors on odd images; that image holds no readable code
                    // then.
                    emptyList()
                }
            }
        val tiled =
            try {
                TiledPass.decode(bitmap.blackMatrix)
            } catch (_: ReaderException) {
                emptyList()
            }
        return (whole + tiled).mapNotNull { result ->
            val format = CodeFormat.of(result.barcodeFormat) ?: return@mapNotNull null
            Found(format, result, if (format.is1D) LinearRows.span(bitmap, result, format) else null)
        }
    }

    /** [codes] without the second finding of a code: same format and text at an overlapping place. */
    internal fun distinct(codes: List<DecodedCode>): List<DecodedCode> {
        val kept = mutableListOf<DecodedCode>()
        for (code in codes) {
            if (kept.none { sameCode(it, code) }) kept += code
        }
        return kept
    }

    /**
     * Two findings are one code when they have the same format and text and their boxes overlap. A linear code is
     * found on any scan line through its bars: another line counts as the same code when their x ranges overlap and
     * the lines are no farther apart than the code is wide.
     */
    private fun sameCode(
        a: DecodedCode,
        b: DecodedCode,
    ): Boolean {
        if (a.format != b.format || a.text != b.text) return false
        if (!a.format.is1D) return a.bounds.intersects(b.bounds)
        val xOverlap = a.bounds.x <= b.bounds.right && b.bounds.x <= a.bounds.right
        val centres = abs((a.bounds.y + a.bounds.height / 2) - (b.bounds.y + b.bounds.height / 2))
        return xOverlap && centres <= maxOf(a.bounds.width, b.bounds.width)
    }

    /**
     * Top to bottom, then left to right: a code starts a new row unless its top lies within half the height of the
     * row's first code below that code's top.
     */
    internal fun inReadingOrder(codes: List<DecodedCode>): List<DecodedCode> {
        val byTop = codes.sortedBy { it.bounds.y }
        val rows = mutableListOf<MutableList<DecodedCode>>()
        for (code in byTop) {
            val first = rows.lastOrNull()?.first()
            if (first != null && code.bounds.y <= first.bounds.y + first.bounds.height / 2) {
                rows.last() += code
            } else {
                rows += mutableListOf(code)
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.bounds.x } }
    }
}
