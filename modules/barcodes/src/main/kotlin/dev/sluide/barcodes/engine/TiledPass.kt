package dev.sluide.barcodes.engine

import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.aztec.AztecReader
import com.google.zxing.common.BitArray
import com.google.zxing.common.BitMatrix
import com.google.zxing.datamatrix.DataMatrixReader

/**
 * Finds Data Matrix and Aztec codes away from the image centre. ZXing's detectors for both grow a white-bordered
 * rectangle from the centre of the image, so they only find a code that covers that centre and has white around it
 * before the image edge. This pass crops windows of the black matrix around a grid of centres instead: a code of size
 * c is found in a window whose centre lies inside the code and whose half side is more than c. Window sides T double
 * from level to level, centres are T/4 apart, so each level finds codes from T/4 to T/2 wide, and together they cover
 * every size from [minCode] to half the image.
 */
internal object TiledPass {
    /** Codes smaller than this many pixels, or 1/64 of the image's longer side, are only found at its centre. */
    const val MIN_CODE = 24

    /** The codes in [matrix], their points in its pixels. */
    fun decode(matrix: BitMatrix): List<Result> {
        val longer = maxOf(matrix.width, matrix.height)
        val minCode = maxOf(MIN_CODE, longer / 64)
        val results = mutableListOf<Result>()
        val rowBuffer = BitArray(matrix.width)
        var tile = 4 * minCode
        while (tile / 4 <= longer / 2) {
            val step = tile / 4
            var centreY = step / 2
            while (centreY < matrix.height) {
                var centreX = step / 2
                while (centreX < matrix.width) {
                    results += decodeWindow(matrix, centreX, centreY, tile / 2, rowBuffer)
                    centreX += step
                }
                centreY += step
            }
            tile *= 2
        }
        return results
    }

    private fun decodeWindow(
        matrix: BitMatrix,
        centreX: Int,
        centreY: Int,
        half: Int,
        rowBuffer: BitArray,
    ): List<Result> {
        // Clipped evenly on both sides, so that the window's centre stays where the detectors start.
        val halfX = minOf(half, centreX, matrix.width - centreX)
        val halfY = minOf(half, centreY, matrix.height - centreY)
        if (halfX < MIN_CODE / 2 || halfY < MIN_CODE / 2) return emptyList()
        val left = centreX - halfX
        val top = centreY - halfY
        val window = crop(matrix, left, top, 2 * halfX, 2 * halfY, rowBuffer)
        val bitmap = BinaryBitmap(FixedBinarizer(window))
        return listOf(DataMatrixReader(), AztecReader()).mapNotNull { reader ->
            try {
                val result = reader.decode(bitmap)
                val points =
                    result.resultPoints
                        .orEmpty()
                        .filterNotNull()
                        .map { ResultPoint(it.x + left, it.y + top) }
                Result(result.text, result.rawBytes, points.toTypedArray(), result.barcodeFormat).also {
                    it.putAllMetadata(result.resultMetadata)
                }
            } catch (_: ReaderException) {
                null
            } catch (_: RuntimeException) {
                // As in the whole-image pass: an index error in a detector means no readable code here.
                null
            }
        }
    }

    /** The [width] × [height] region of [matrix] at ([left], [top]), copied a word at a time. */
    internal fun crop(
        matrix: BitMatrix,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        rowBuffer: BitArray = BitArray(matrix.width),
    ): BitMatrix {
        val out = BitMatrix(width, height)
        val target = BitArray(width)
        val targetWords = target.bitArray
        val shift = left and 31
        val firstWord = left ushr 5
        val lastMask = if (width and 31 == 0) -1 else (1 shl (width and 31)) - 1
        for (y in 0 until height) {
            val source = matrix.getRow(top + y, rowBuffer).bitArray
            for (i in targetWords.indices) {
                val low = source[firstWord + i] ushr shift
                val high =
                    if (shift == 0 ||
                        firstWord + i + 1 >= source.size
                    ) {
                        0
                    } else {
                        source[firstWord + i + 1] shl (32 - shift)
                    }
                targetWords[i] = low or high
            }
            targetWords[targetWords.size - 1] = targetWords[targetWords.size - 1] and lastMask
            out.setRow(y, target)
        }
        return out
    }

    /** Hands the readers a black matrix that is already there; they ask for nothing else. */
    private class FixedBinarizer(
        private val matrix: BitMatrix,
    ) : Binarizer(SizeOnly(matrix.width, matrix.height)) {
        override fun getBlackRow(
            y: Int,
            row: BitArray?,
        ): BitArray = matrix.getRow(y, row)

        override fun getBlackMatrix(): BitMatrix = matrix

        override fun createBinarizer(source: LuminanceSource): Binarizer = throw UnsupportedOperationException()
    }

    private class SizeOnly(
        width: Int,
        height: Int,
    ) : LuminanceSource(width, height) {
        override fun getRow(
            y: Int,
            row: ByteArray?,
        ): ByteArray = ByteArray(width)

        override fun getMatrix(): ByteArray = ByteArray(width * height)
    }
}
