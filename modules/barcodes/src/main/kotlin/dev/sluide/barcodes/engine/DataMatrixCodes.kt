package dev.sluide.barcodes.engine

import com.google.zxing.datamatrix.encoder.DefaultPlacement
import com.google.zxing.datamatrix.encoder.ErrorCorrection
import com.google.zxing.datamatrix.encoder.HighLevelEncoder
import com.google.zxing.datamatrix.encoder.SymbolInfo
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Data Matrix symbols from ZXing's parts, always square, as phone apps expect. Latin-1 text goes through ZXing's
 * high-level encoder. Other text goes as its UTF-8 bytes behind an ECI, in one Base256 field: ZXing's own ECI-aware
 * encoder checks every UTF-16 unit on its own and fails on characters outside the BMP (emoji).
 */
internal object DataMatrixCodes {
    private const val ECI = 241
    private const val UTF_8_ECI = 26
    private const val LATCH_TO_BASE256 = 231
    private const val PAD = 129

    /** The modules of [text]; throws IllegalArgumentException when no symbol is large enough. */
    fun grid(text: String): ModuleGrid {
        val latin1 = Generator.charsetFor(text) == null
        val codewords =
            if (latin1) {
                HighLevelEncoder.encodeHighLevel(
                    text,
                    SymbolShapeHint.FORCE_SQUARE,
                    null,
                    null,
                )
            } else {
                utf8Codewords(text)
            }
        val info = SymbolInfo.lookup(codewords.length, SymbolShapeHint.FORCE_SQUARE, null, null, true)
        val placement =
            DefaultPlacement(ErrorCorrection.encodeECC200(codewords, info), info.symbolDataWidth, info.symbolDataHeight)
        placement.place()
        return layout(placement, info)
    }

    /** ECI 26 (UTF-8), then the bytes in one Base256 field, padded to the symbol's capacity. */
    private fun utf8Codewords(text: String): String {
        val bytes = text.toByteArray(UTF_8)
        val codewords = StringBuilder()
        codewords.append(ECI.toChar()).append((UTF_8_ECI + 1).toChar()).append(LATCH_TO_BASE256.toChar())
        val length =
            if (bytes.size <= 249) {
                listOf(bytes.size)
            } else {
                listOf(bytes.size / 250 + 249, bytes.size % 250)
            }
        for (value in length + bytes.map { it.toInt() and 0xFF }) {
            codewords.append(randomize255(value, codewords.length + 1).toChar())
        }
        val capacity = SymbolInfo.lookup(codewords.length, SymbolShapeHint.FORCE_SQUARE, null, null, true).dataCapacity
        if (codewords.length < capacity) codewords.append(PAD.toChar())
        while (codewords.length < capacity) codewords.append(randomize253(codewords.length + 1).toChar())
        return codewords.toString()
    }

    /** The 255-state randomizing of Base256 codewords (ISO/IEC 16022, annex B). */
    private fun randomize255(
        value: Int,
        position: Int,
    ): Int {
        val randomized = value + (149 * position) % 255 + 1
        return if (randomized <= 255) randomized else randomized - 256
    }

    /** The 253-state randomizing of the pad codewords after the first (ISO/IEC 16022, 5.2.3). */
    private fun randomize253(position: Int): Int {
        val randomized = PAD + (149 * position) % 253 + 1
        return if (randomized <= 254) randomized else randomized - 254
    }

    /** The data regions with their finder and timing edges, as ZXing's DataMatrixWriter lays them out. */
    private fun layout(
        placement: DefaultPlacement,
        info: SymbolInfo,
    ): ModuleGrid {
        val width = info.symbolWidth
        val bits = BooleanArray(width * info.symbolHeight)
        var matrixY = 0
        for (y in 0 until info.symbolDataHeight) {
            if (y % info.matrixHeight == 0) {
                for (x in 0 until width) bits[matrixY * width + x] = x % 2 == 0
                matrixY++
            }
            var matrixX = 0
            for (x in 0 until info.symbolDataWidth) {
                if (x % info.matrixWidth == 0) bits[matrixY * width + matrixX++] = true
                bits[matrixY * width + matrixX++] = placement.getBit(x, y)
                if (x % info.matrixWidth == info.matrixWidth - 1) bits[matrixY * width + matrixX++] = y % 2 == 0
            }
            matrixY++
            if (y % info.matrixHeight == info.matrixHeight - 1) {
                for (x in 0 until width) bits[matrixY * width + x] = true
                matrixY++
            }
        }
        return ModuleGrid(width, info.symbolHeight, bits)
    }
}
