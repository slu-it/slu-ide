package dev.sluide.barcodes.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.charset.StandardCharsets.UTF_8

internal class GeneratorTest {
    private fun code(
        text: String,
        format: CodeFormat,
        options: GenerateOptions = GenerateOptions(),
    ) = Generator.generate(text, format, options) as Generated.Code

    private fun failure(
        text: String,
        format: CodeFormat,
        options: GenerateOptions = GenerateOptions(),
    ) = Generator.generate(text, format, options) as Generated.Failure

    @Test
    fun `empty text gives no code`() {
        assertNull(Generator.generate("", CodeFormat.QR_CODE, GenerateOptions()))
    }

    @Test
    fun `QR code is the raw module matrix with version, size and ECC in the summary`() {
        val qr = code("Hello", CodeFormat.QR_CODE)
        assertEquals("QR version 1 · 21×21 · ECC M", qr.summary)
        assertEquals(21, qr.grid.width)
        // The finder pattern: dark corner module, light separator column after its 7 modules.
        assertTrue(qr.grid[0, 0])
        assertTrue(qr.grid[6, 0])
        assertTrue(!qr.grid[7, 0])
        assertEquals(
            "QR version 2 · 25×25 · ECC H",
            code("Hello, world!", CodeFormat.QR_CODE, GenerateOptions(QrEcc.H)).summary,
        )
    }

    @Test
    fun `Data Matrix grid starts with its solid L finder`() {
        // 10 ASCII codewords: without the square rule ZXing picks the 8×32 rectangle.
        val dm = code("a.b,c;d!e?", CodeFormat.DATA_MATRIX)
        val grid = dm.grid
        assertEquals("Data Matrix · ${grid.width}×${grid.height}", dm.summary)
        assertEquals(grid.width, grid.height, "always square, also where a rectangle would be smaller")
        assertTrue((0 until grid.height).all { grid[0, it] }, "left column dark")
        assertTrue((0 until grid.width).all { grid[it, grid.height - 1] }, "bottom row dark")
        assertTrue(!grid[1, 0], "top edge alternates")
    }

    @Test
    fun `Aztec summary names size and ECC percent`() {
        val aztec = code("Hello", CodeFormat.AZTEC, GenerateOptions(aztecEccPercent = 40))
        assertEquals("Aztec · 15×15 · 40 % ECC", aztec.summary)
        // The centre of the bull's eye is dark.
        assertTrue(aztec.grid[7, 7])
    }

    @Test
    fun `PDF417 rows are four modules high, start pattern first`() {
        val pdf = code("Hello", CodeFormat.PDF_417)
        val grid = pdf.grid
        assertEquals("PDF417 · ${grid.width}×${grid.height} modules · level 2", pdf.summary)
        assertEquals(0, (grid.width - 1) % 17, "start, indicators, data columns and stop are 17 modules, plus 1")
        assertEquals(0, grid.height % 4)
        // The start pattern 81111113 begins with 8 dark modules on every row.
        assertTrue((0 until grid.height).all { y -> (0 until 8).all { grid[it, y] } && !grid[8, y] })
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "H|a|1300|Too long for QR Code at ECC H: 1 300 of 1 273 bytes",
            // UTF-8 with the ECI header: 3 bytes each, 12 bits less room.
            "H|€|425|Too long for QR Code at ECC H: 1 275 of 1 272 bytes",
            "H|1|3100|Too long for QR Code at ECC H: 3 100 of 3 057 digits",
            "H|A|1900|Too long for QR Code at ECC H: 1 900 of 1 852 characters",
            "L|a|3000|Too long for QR Code at ECC L: 3 000 of 2 953 bytes",
        ],
    )
    fun `QR capacity error names the length and the maximum of version 40`(
        ecc: QrEcc,
        unit: String,
        count: Int,
        message: String,
    ) {
        assertEquals(Generated.Failure(message), failure(unit.repeat(count), CodeFormat.QR_CODE, GenerateOptions(ecc)))
    }

    @Test
    fun `text too long for ECC H fits at ECC L, and the maximum at ECC H fits too`() {
        val text = "a".repeat(1300)
        assertEquals(
            "QR version 26 · 121×121 · ECC L",
            code(text, CodeFormat.QR_CODE, GenerateOptions(QrEcc.L)).summary,
        )
        val maximum = code(text.take(1273), CodeFormat.QR_CODE, GenerateOptions(QrEcc.H))
        assertEquals("QR version 40 · 177×177 · ECC H", maximum.summary)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "DATA_MATRIX|Too long for Data Matrix: 4 000 bytes",
            "AZTEC|Too long for Aztec at 23 % ECC: 4 000 bytes",
            "PDF_417|Too long for PDF417 at level 2: 4 000 bytes",
        ],
    )
    fun `other 2D formats name the byte count when too long`(
        format: CodeFormat,
        message: String,
    ) {
        assertEquals(Generated.Failure(message), failure("a".repeat(4000), format))
    }

    @Test
    fun `an error ZXing throws becomes a failure`() {
        val failure = failure("Hello", CodeFormat.PDF_417, GenerateOptions(pdf417Level = 9))
        assertTrue(failure.message.startsWith("Cannot encode as PDF417: "), failure.message)
    }

    @Test
    fun `read-only formats are not generated`() {
        assertEquals(Generated.Failure("EAN-8 can only be read, not generated"), failure("1234567", CodeFormat.EAN_8))
    }

    @Test
    fun `Latin-1 text needs no character set, anything else goes as UTF-8`() {
        assertNull(Generator.charsetFor("Grüße, café"))
        assertEquals(UTF_8, Generator.charsetFor("日本語"))
        assertEquals(UTF_8, Generator.charsetFor("€"))
    }

    @ParameterizedTest
    @CsvSource(
        "EAN_13, 400638133393, EAN-13 · 4006381333931",
        "EAN_13, 4006381333931, EAN-13 · 4006381333931",
        "UPC_A, 03600029145, UPC-A · 036000291452",
        "UPC_A, 036000291452, UPC-A · 036000291452",
    )
    fun `EAN-13 and UPC-A append or verify the check digit`(
        format: CodeFormat,
        text: String,
        summary: String,
    ) {
        val code = code(text, format)
        assertEquals(summary, code.summary)
        assertEquals(95, code.grid.width)
        assertEquals(1, code.grid.height)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "EAN_13|4006381333935|Wrong check digit 5; expected 1|12",
            "UPC_A|036000291453|Wrong check digit 3; expected 2|11",
            "EAN_13|123456789|EAN-13 takes 12 or 13 digits (has 9)|",
            "UPC_A|12345678901234|UPC-A takes 11 or 12 digits (has 14)|",
            "EAN_13|400x6381333|EAN-13 takes digits only: 'x' (character 4)|3",
            "UPC_A|03600 029145|UPC-A takes digits only: U+0020 (character 6)|5",
        ],
    )
    fun `EAN-13 and UPC-A refuse wrong input with the position`(
        format: CodeFormat,
        text: String,
        message: String,
        index: Int?,
    ) {
        assertEquals(Generated.Failure(message, index), failure(text, format))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Hi é|Code 128 takes ASCII only: 'é' (character 4)|3",
            "a→b|Code 128 takes ASCII only: '→' (character 2)|1",
            "x😀|Code 128 takes ASCII only: '😀' (character 2)|1",
        ],
    )
    fun `Code 128 refuses non-ASCII characters with the position`(
        text: String,
        message: String,
        index: Int,
    ) {
        assertEquals(Generated.Failure(message, index), failure(text, CodeFormat.CODE_128))
    }

    @Test
    fun `Code 128 takes at most 80 characters`() {
        assertTrue(Generator.generate("a".repeat(80), CodeFormat.CODE_128, GenerateOptions()) is Generated.Code)
        assertEquals(
            Generated.Failure("Too long for Code 128: 4 096 of 80 characters"),
            failure("a".repeat(4096), CodeFormat.CODE_128),
        )
    }

    @Test
    fun `a code over 4096 modules per side with its quiet zone is not drawn`() {
        // Version 1 QR: 21 modules plus 2 × 2038 = 4097.
        assertEquals(
            Generated.Failure("Too large to draw: 4 097 modules wide with the quiet zone (at most 4 096)"),
            failure("Hello", CodeFormat.QR_CODE, GenerateOptions(quietZone = 2038)),
        )
        assertTrue(Generator.generate("Hello", CodeFormat.QR_CODE, GenerateOptions(quietZone = 2037)) is Generated.Code)
    }

    @Test
    fun `Code 128 takes control characters and counts its modules`() {
        val code = code("a\tb", CodeFormat.CODE_128)
        assertEquals("Code 128 · ${code.grid.width} modules", code.summary)
        assertEquals(1, code.grid.height)
        assertTrue(code.grid[0, 0])
    }

    @Test
    fun `default quiet zones follow the format`() {
        val defaults = GenerateOptions()
        assertEquals(
            listOf(4, 1, 1, 2, 10, 10, 10),
            listOf(
                CodeFormat.QR_CODE,
                CodeFormat.DATA_MATRIX,
                CodeFormat.AZTEC,
                CodeFormat.PDF_417,
                CodeFormat.CODE_128,
                CodeFormat.EAN_13,
                CodeFormat.UPC_A,
            ).map { defaults.quietZoneFor(it) },
        )
        assertEquals(3, GenerateOptions(quietZone = 3).quietZoneFor(CodeFormat.EAN_13))
    }
}
