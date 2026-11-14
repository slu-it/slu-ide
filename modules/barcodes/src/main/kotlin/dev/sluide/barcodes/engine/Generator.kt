package dev.sluide.barcodes.engine

import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.pdf417.encoder.Compaction
import com.google.zxing.pdf417.encoder.PDF417
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.Encoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import com.google.zxing.aztec.encoder.Encoder as AztecEncoder

/**
 * Turns text into the module grid of a code. Takes ZXing's raw modules, one entry per module, so that the export can
 * scale by a whole number and stay crisp. Fast enough for the FX thread with texts of a few kilobytes.
 */
internal object Generator {
    /** PDF417 rows are drawn this many modules high, as ZXing's own writer does. */
    private const val PDF417_ROW_HEIGHT = 4

    /**
     * The most characters any format is asked to encode: QR's numeric maximum at ECC L, the largest capacity of all.
     * Longer text is refused before encoding, so that the generation on every keystroke stays fast on the FX thread.
     */
    const val MAX_TEXT_LENGTH = 7089

    /** The code for [text], a [Generated.Failure] saying why it cannot be one, or null for an empty text. */
    fun generate(
        text: String,
        format: CodeFormat,
        options: GenerateOptions,
    ): Generated? {
        if (text.isEmpty()) return null
        if (!format.generatable) return Generated.Failure("${format.title} can only be read, not generated")
        if (text.length > MAX_TEXT_LENGTH) {
            return Generated.Failure(
                "Too long for ${format.title}: ${grouped(text.length)} characters; " +
                    "no barcode holds more than ${grouped(MAX_TEXT_LENGTH)}",
            )
        }
        val generated =
            try {
                when (format) {
                    CodeFormat.QR_CODE -> qr(text, options.qrEcc)
                    CodeFormat.DATA_MATRIX -> dataMatrix(text)
                    CodeFormat.AZTEC -> aztec(text, options.aztecEccPercent)
                    CodeFormat.PDF_417 -> pdf417(text, options.pdf417Level)
                    else -> LinearCodes.generate(text, format)
                }
            } catch (e: WriterException) {
                Generated.Failure("Cannot encode as ${format.title}: ${e.message}")
            } catch (e: RuntimeException) {
                // Runs on every keystroke: whatever ZXing throws for odd input becomes a message, never a crash.
                Generated.Failure("Cannot encode as ${format.title}: ${e.message}")
            }
        return if (generated is Generated.Code) tooLargeToDraw(generated, options) ?: generated else generated
    }

    /** At most this many modules per side, quiet zone included, so that the export stays a sane size. */
    const val MAX_MODULES = 4096

    private fun tooLargeToDraw(
        code: Generated.Code,
        options: GenerateOptions,
    ): Generated.Failure? {
        val quiet = options.quietZoneFor(code.format).coerceAtLeast(0).toLong()
        val width = code.grid.width + 2 * quiet
        val height = if (code.format.is1D) 1 else code.grid.height + 2 * quiet
        val (side, modules) = if (width >= height) "wide" to width else "high" to height
        if (modules <= MAX_MODULES) return null
        return Generated.Failure(
            "Too large to draw: ${grouped(
                modules,
            )} modules $side with the quiet zone (at most ${grouped(MAX_MODULES)})",
        )
    }

    /**
     * Latin-1, the default of the 2D formats, needs no character set: null then, so no ECI is written and old
     * scanners read it. Anything else goes as UTF-8 with an ECI.
     */
    internal fun charsetFor(text: String): Charset? = if (ISO_8859_1.newEncoder().canEncode(text)) null else UTF_8

    private fun byteCount(text: String) = text.toByteArray(charsetFor(text) ?: ISO_8859_1).size

    private fun qr(
        text: String,
        ecc: QrEcc,
    ): Generated {
        val level = ErrorCorrectionLevel.valueOf(ecc.name)
        val charset = charsetFor(text)
        val hints = charset?.let { mapOf(EncodeHintType.CHARACTER_SET to it.name()) }
        val code =
            try {
                Encoder.encode(text, level, hints)
            } catch (e: WriterException) {
                return qrTooLong(text, level, charset) ?: throw e
            }
        val matrix = code.matrix
        val grid = gridOf(matrix.width, matrix.height) { x, y -> matrix.get(x, y).toInt() == 1 }
        val size = "${matrix.width}×${matrix.height}"
        return Generated.Code(grid, CodeFormat.QR_CODE, "QR version ${code.version.versionNumber} · $size · ECC $ecc")
    }

    /** The capacity message when [text] does not fit the largest QR version (40), null when it does. */
    private fun qrTooLong(
        text: String,
        level: ErrorCorrectionLevel,
        charset: Charset?,
    ): Generated.Failure? {
        val mode = Encoder.chooseMode(text)
        val version = Version.getVersionForNumber(40)
        val dataBits = (version.totalCodewords - version.getECBlocksForLevel(level).totalECCodewords) * 8
        val eciBits = if (mode == Mode.BYTE && charset != null) 12 else 0
        val available = dataBits - eciBits - 4 - mode.getCharacterCountBits(version)
        val (length, maximum, unit) =
            when (mode) {
                Mode.NUMERIC -> {
                    Triple(text.length, available / 10 * 3 + numericRest(available % 10), "digits")
                }

                Mode.ALPHANUMERIC -> {
                    Triple(text.length, available / 11 * 2 + (if (available % 11 >= 6) 1 else 0), "characters")
                }

                else -> {
                    Triple(byteCount(text), available / 8, "bytes")
                }
            }
        if (length <= maximum) return null
        return Generated.Failure(
            "Too long for QR Code at ECC ${level.name}: ${grouped(length)} of ${grouped(maximum)} $unit",
        )
    }

    private fun numericRest(bits: Int) =
        when {
            bits >= 7 -> 2
            bits >= 4 -> 1
            else -> 0
        }

    private fun dataMatrix(text: String): Generated {
        val grid =
            try {
                DataMatrixCodes.grid(text)
            } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("Can't find a symbol arrangement") != true) throw e
                return Generated.Failure("Too long for Data Matrix: ${grouped(byteCount(text))} bytes")
            }
        return Generated.Code(grid, CodeFormat.DATA_MATRIX, "Data Matrix · ${grid.width}×${grid.height}")
    }

    private fun aztec(
        text: String,
        eccPercent: Int,
    ): Generated {
        val code =
            try {
                AztecEncoder.encode(text, eccPercent, 0, charsetFor(text))
            } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("Data too large") != true) throw e
                return Generated.Failure("Too long for Aztec at $eccPercent % ECC: ${grouped(byteCount(text))} bytes")
            }
        val matrix = code.matrix
        val grid = gridOf(matrix.width, matrix.height) { x, y -> matrix.get(x, y) }
        return Generated.Code(grid, CodeFormat.AZTEC, "Aztec · ${matrix.width}×${matrix.height} · $eccPercent % ECC")
    }

    private fun pdf417(
        text: String,
        level: Int,
    ): Generated {
        val encoder = PDF417()
        charsetFor(text)?.let {
            encoder.setEncoding(it)
            // Text and numeric compaction check every UTF-16 unit on its own and refuse emoji; bytes take everything.
            encoder.setCompaction(Compaction.BYTE)
        }
        try {
            encoder.generateBarcodeLogic(text, level, false)
        } catch (e: WriterException) {
            val message = e.message.orEmpty()
            if (!message.startsWith("Encoded message contains too many code words") &&
                !message.startsWith("Unable to fit message")
            ) {
                throw e
            }
            return Generated.Failure("Too long for PDF417 at level $level: ${grouped(byteCount(text))} bytes")
        }
        // ZXing keeps the rows bottom-up: the last array row is the first row of the symbol.
        val rows = encoder.barcodeMatrix.getScaledMatrix(1, 1).reversedArray()
        val width = rows.first().size
        val grid = gridOf(width, rows.size * PDF417_ROW_HEIGHT) { x, y -> rows[y / PDF417_ROW_HEIGHT][x].toInt() == 1 }
        return Generated.Code(grid, CodeFormat.PDF_417, "PDF417 · $width×${grid.height} modules · level $level")
    }
}

internal inline fun gridOf(
    width: Int,
    height: Int,
    dark: (Int, Int) -> Boolean,
): ModuleGrid {
    val bits = BooleanArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) bits[y * width + x] = dark(x, y)
    }
    return ModuleGrid(width, height, bits)
}

/** A count with a space between the thousands, as in "1 273", the way the messages of Codecs write them. */
internal fun grouped(count: Number): String = "%,d".format(Locale.ROOT, count.toLong()).replace(',', ' ')

/** How a message names the character at [index]: quoted, or as U+XXXX for control and space characters. */
internal fun describeChar(
    text: String,
    index: Int,
): String {
    val codePoint = text.codePointAt(index)
    return if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint) ||
        Character.isSpaceChar(codePoint)
    ) {
        "U+%04X".format(codePoint)
    } else {
        "'${String(Character.toChars(codePoint))}'"
    }
}
