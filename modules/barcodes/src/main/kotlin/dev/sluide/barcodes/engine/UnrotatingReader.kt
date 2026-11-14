package dev.sluide.barcodes.engine

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.Reader
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.ResultPoint

/**
 * [MultiFormatReader] with PDF417 points in the frame of the bitmap it was handed. ZXing's PDF417 reader tries the
 * black matrix turned by 180°, 270° and 90° too and returns the points of the turned matrix, saying only the turn in
 * [ResultMetadataType.ORIENTATION]. They are turned back here, before `GenericMultipleBarcodeReader` adds the offset
 * of its crop. The linear readers turn their points back themselves.
 */
internal class UnrotatingReader(
    private val delegate: MultiFormatReader = MultiFormatReader(),
) : Reader {
    override fun decode(image: BinaryBitmap): Result = decode(image, null)

    override fun decode(
        image: BinaryBitmap,
        hints: Map<DecodeHintType, *>?,
    ): Result = unrotated(delegate.decode(image, hints), image.width, image.height)

    override fun reset() = delegate.reset()

    internal companion object {
        /**
         * [result] with its points turned back into the frame of a [width] × [height] bitmap, when it is a PDF417
         * found in a turned matrix. ZXing turns the matrix counterclockwise: 90° maps (x, y) to (y, width − x), 180°
         * to (width − x, height − y), 270° to (height − y, x).
         */
        fun unrotated(
            result: Result,
            width: Int,
            height: Int,
        ): Result {
            val turn = result.resultMetadata?.get(ResultMetadataType.ORIENTATION) as? Int ?: 0
            if (result.barcodeFormat != BarcodeFormat.PDF_417 || turn % 360 == 0) return result
            val w = width.toFloat()
            val h = height.toFloat()
            val points =
                result.resultPoints.orEmpty().map { point ->
                    point?.let {
                        when (turn % 360) {
                            90 -> ResultPoint(w - it.y, it.x)
                            180 -> ResultPoint(w - it.x, h - it.y)
                            else -> ResultPoint(it.y, h - it.x)
                        }
                    }
                }
            return Result(result.text, result.rawBytes, points.toTypedArray(), result.barcodeFormat, result.timestamp)
                .also {
                    it.putAllMetadata(result.resultMetadata)
                    it.putMetadata(ResultMetadataType.ORIENTATION, 0)
                }
        }
    }
}
