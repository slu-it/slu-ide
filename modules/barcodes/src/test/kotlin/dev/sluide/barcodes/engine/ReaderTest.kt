package dev.sluide.barcodes.engine

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

internal class ReaderTest {
    private fun readOne(image: ArgbImage): DecodedCode {
        val codes = Reader.read(image)
        assertEquals(1, codes.size, "$codes")
        return codes.single()
    }

    @ParameterizedTest
    @CsvSource(
        "QR_CODE, https://example.com/?q=1&r=2",
        "DATA_MATRIX, Thoreau 1.0",
        "AZTEC, Thoreau 1.0",
        "PDF_417, Thoreau 1.0",
        "CODE_128, Thoreau-128",
        "EAN_13, 4006381333931",
        "UPC_A, 036000291452",
    )
    fun `every generatable format reads back`(
        format: CodeFormat,
        text: String,
    ) {
        val decoded = readOne(codeImage(text, format))
        assertEquals(format, decoded.format)
        assertEquals(text, decoded.text)
    }

    @ParameterizedTest
    @CsvSource(
        "QR_CODE, 日本語のテキスト 😀",
        "DATA_MATRIX, 日本語のテキスト 😀",
        "AZTEC, 日本語のテキスト 😀",
        "PDF_417, 日本語のテキスト 😀",
        "QR_CODE, Grüße",
        "DATA_MATRIX, Grüße",
        "AZTEC, Grüße",
        "PDF_417, Grüße",
    )
    fun `Unicode and Latin-1 text read back exactly`(
        format: CodeFormat,
        text: String,
    ) {
        assertEquals(text, readOne(codeImage(text, format)).text)
    }

    @ParameterizedTest
    @CsvSource("Grüße, ]Q1", "日本語, ]Q2")
    fun `QR codes carry an ECI only for text outside Latin-1`(
        text: String,
        symbology: String,
    ) {
        // ]Q2 is the symbology identifier of a QR code with an ECI, ]Q1 one without.
        val image = codeImage(text, CodeFormat.QR_CODE)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, image.argb)))
        val result = QRCodeReader().decode(bitmap)
        assertEquals(text, result.text)
        assertEquals(symbology, result.resultMetadata[ResultMetadataType.SYMBOLOGY_IDENTIFIER])
    }

    @Test
    fun `codes side by side read left to right, stacked ones top to bottom`() {
        val options = GenerateOptions(sizePx = 200)
        val left = codeImage("left", CodeFormat.QR_CODE, options)
        val right = codeImage("right", CodeFormat.QR_CODE, options)
        // The right code sits a little higher: it is still in the same row.
        val sideBySide = compose(460, 260, Triple(left, 10, 40), Triple(right, 250, 20))
        assertEquals(listOf("left", "right"), Reader.read(sideBySide).map { it.text })

        val top = codeImage("top", CodeFormat.QR_CODE, options)
        val bottom = codeImage("bottom", CodeFormat.QR_CODE, options)
        val stacked = compose(260, 460, Triple(bottom, 30, 250), Triple(top, 50, 10))
        val codes = Reader.read(stacked)
        assertEquals(listOf("top", "bottom"), codes.map { it.text })
        assertTrue(codes[0].bounds.inside(Box(50.0, 10.0, top.width.toDouble(), top.height.toDouble())))
        assertTrue(codes[1].bounds.inside(Box(30.0, 250.0, bottom.width.toDouble(), bottom.height.toDouble())))
    }

    @Test
    fun `light-on-dark code reads`() {
        val inverted = GenerateOptions(foreground = WHITE, background = 0xFF000000.toInt())
        assertEquals("inverted", readOne(codeImage("inverted", CodeFormat.QR_CODE, inverted)).text)
    }

    @Test
    fun `rotated code reads`() {
        assertEquals("turned", readOne(rotatedClockwise(codeImage("turned", CodeFormat.QR_CODE))).text)
    }

    @Test
    fun `code on a transparent background reads`() {
        // Transparent black: read without compositing over white, it would be black on black.
        val transparent = GenerateOptions(background = 0)
        assertEquals("clear", readOne(codeImage("clear", CodeFormat.QR_CODE, transparent)).text)
    }

    @Test
    fun `hostile images give no codes`() {
        assertEquals(emptyList<DecodedCode>(), Reader.read(ArgbImage(0, 0, IntArray(0))))
        assertEquals(emptyList<DecodedCode>(), Reader.read(ArgbImage(1, 1, intArrayOf(WHITE))))
        assertEquals(emptyList<DecodedCode>(), Reader.read(ArgbImage(10000, 1, IntArray(10000) { WHITE })))
        val random = Random(42)
        val noise = ArgbImage(500, 500, IntArray(500 * 500) { if (random.nextBoolean()) WHITE else 0xFF000000.toInt() })
        assertEquals(emptyList<DecodedCode>(), Reader.read(noise))
    }

    @Test
    fun `a code in an image larger than the limit reads, with bounds in the original pixels`() {
        val code = codeImage("far corner", CodeFormat.QR_CODE, GenerateOptions(sizePx = 600))
        val big = compose(5000, 5000, Triple(code, 4200, 3900))
        val decoded = readOne(big)
        assertEquals("far corner", decoded.text)
        assertTrue(
            decoded.bounds.inside(Box(4200.0, 3900.0, code.width.toDouble(), code.height.toDouble())),
            "${decoded.bounds}",
        )
    }

    @Test
    fun `Data Matrix with a two-codeword Base256 length reads back`() {
        // 300 UTF-8 bytes: the Base256 length needs two codewords from 250 on.
        val text = "日".repeat(100)
        assertEquals(text, readOne(codeImage(text, CodeFormat.DATA_MATRIX)).text)
    }

    @ParameterizedTest
    @CsvSource(
        "DATA_MATRIX, 20, 20, 800, 600",
        "DATA_MATRIX, 1500, 700, 1920, 1080",
        "AZTEC, 100, 100, 1920, 1080",
        "AZTEC, 1600, 800, 1920, 1080",
    )
    fun `Data Matrix and Aztec away from the image centre read`(
        format: CodeFormat,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        val code = codeImage("off centre", format, GenerateOptions(sizePx = 200))
        val decoded = readOne(compose(width, height, Triple(code, left, top)))
        assertEquals(format to "off centre", decoded.format to decoded.text)
    }

    @Test
    fun `two Data Matrix codes in different corners both read`() {
        val options = GenerateOptions(sizePx = 160)
        val first = codeImage("first", CodeFormat.DATA_MATRIX, options)
        val second = codeImage("second", CodeFormat.DATA_MATRIX, options)
        val image = compose(1920, 1080, Triple(first, 30, 40), Triple(second, 1700, 880))
        assertEquals(listOf("first", "second"), Reader.read(image).map { it.text })
    }

    @Test
    fun `codes of other formats in one image all read`() {
        val options = GenerateOptions(sizePx = 300)
        val qr = codeImage("qr", CodeFormat.QR_CODE, options)
        val ean = codeImage("4006381333931", CodeFormat.EAN_13, options)
        val codes = Reader.read(compose(400, 600, Triple(qr, 20, 20), Triple(ean, 20, 400)))
        assertEquals(
            listOf(CodeFormat.QR_CODE to "qr", CodeFormat.EAN_13 to "4006381333931"),
            codes.map {
                it.format to
                    it.text
            },
        )
    }

    @Test
    fun `a linear code on a low-contrast background reads`() {
        // Rows of grey noise in a narrow band have no clear black point: ZXing's row binarizer throws on them, which
        // the probing for a linear code's height meets in the rows above and below the bars.
        val random = Random(7)
        val noise = IntArray(900 * 500) { 0xFF shl 24 or (0x010101 * (120 + random.nextInt(12))) }
        val ean = codeImage("4006381333931", CodeFormat.EAN_13, GenerateOptions(sizePx = 300))
        val image = compose(900, 500, Triple(ean, 300, 200), background = 0)
        val pixels = IntArray(image.argb.size) { if (image.argb[it] == 0) noise[it] else image.argb[it] }
        val codes = Reader.read(ArgbImage(900, 500, pixels))
        assertEquals(listOf(CodeFormat.EAN_13 to "4006381333931"), codes.map { it.format to it.text })
    }

    private fun Box.inside(outer: Box) = x >= outer.x && y >= outer.y && right <= outer.right && bottom <= outer.bottom
}
