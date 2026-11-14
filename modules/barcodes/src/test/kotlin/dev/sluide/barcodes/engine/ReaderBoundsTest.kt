package dev.sluide.barcodes.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The bounds the reader gives cover the whole symbol and little more, for every format and after the downscale. */
internal class ReaderBoundsTest {
    private val options = GenerateOptions(sizePx = 300)

    /** The code placed at [left], [top] after [turns] quarter turns clockwise, and where its modules lie in the image. */
    private fun placed(
        text: String,
        format: CodeFormat,
        turns: Int,
        left: Int,
        top: Int,
    ): Pair<ArgbImage, Box> {
        var image = codeImage(text, format, options)
        var area = moduleArea(text, format, options)
        repeat(turns) {
            // Clockwise: (x, y) in an image of height h goes to (h − y, x).
            area = Box(image.height - area.bottom, area.x, area.height, area.width)
            image = rotatedClockwise(image)
        }
        return image to Box(area.x + left, area.y + top, area.width, area.height)
    }

    @ParameterizedTest
    @CsvSource(
        "QR_CODE, https://example.com/?q=1&r=2, 800, 700, 0, 1.0",
        "DATA_MATRIX, Thoreau 1.0, 800, 700, 0, 1.0",
        "AZTEC, Thoreau 1.0, 800, 700, 0, 1.0",
        "PDF_417, Thoreau 1.0, 800, 700, 0, 1.0",
        // ZXing's PDF417 reader finds turned codes in a turned matrix and reports its points there.
        "PDF_417, Thoreau 1.0, 1600, 1000, 1, 1.0",
        "PDF_417, Thoreau 1.0, 1600, 1000, 2, 1.0",
        "PDF_417, Thoreau 1.0, 1600, 1000, 3, 1.0",
        // Scaled down from 5000 to 4096: a sample pixel is 1.22 original pixels.
        "QR_CODE, https://example.com/?q=1&r=2, 5000, 4200, 0, 2.5",
        "DATA_MATRIX, Thoreau 1.0, 5000, 4200, 0, 2.5",
        "AZTEC, Thoreau 1.0, 5000, 4200, 0, 2.5",
        "PDF_417, Thoreau 1.0, 5000, 4200, 0, 2.5",
    )
    fun `2D bounds cover the code's modules and are at most 15 percent larger per side`(
        format: CodeFormat,
        text: String,
        width: Int,
        height: Int,
        turns: Int,
        slack: Double,
    ) {
        val (image, modules) = placed(text, format, turns, 300, 200)
        val bounds = readOne(compose(width, height, Triple(image, 300, 200)), format, text)
        val message = "bounds $bounds, modules $modules"
        val marginX = 0.15 * modules.width
        val marginY = 0.15 * modules.height
        assertTrue(bounds.x <= modules.x + slack && bounds.x >= modules.x - marginX, message)
        assertTrue(bounds.right >= modules.right - slack && bounds.right <= modules.right + marginX, message)
        assertTrue(bounds.y <= modules.y + slack && bounds.y >= modules.y - marginY, message)
        assertTrue(bounds.bottom >= modules.bottom - slack && bounds.bottom <= modules.bottom + marginY, message)
    }

    @ParameterizedTest
    @CsvSource(
        // Above and below the image's middle row, where ZXing starts its scan lines.
        "CODE_128, Thoreau-128, 800, 700, 80, 1.0",
        "CODE_128, Thoreau-128, 800, 700, 420, 1.0",
        "EAN_13, 4006381333931, 800, 700, 80, 1.0",
        "EAN_13, 4006381333931, 800, 700, 420, 1.0",
        "UPC_A, 036000291452, 800, 700, 80, 1.0",
        "UPC_A, 036000291452, 800, 700, 420, 1.0",
        "EAN_13, 4006381333931, 5000, 4200, 1500, 2.5",
        "EAN_13, 4006381333931, 5000, 4200, 2500, 2.5",
    )
    fun `linear bounds span the bars' width and at least 90 percent of their height`(
        format: CodeFormat,
        text: String,
        width: Int,
        height: Int,
        top: Int,
        slack: Double,
    ) {
        val (image, bars) = placed(text, format, 0, 150, top)
        val bounds = readOne(compose(width, height, Triple(image, 150, top)), format, text)
        val message = "bounds $bounds, bars $bars"
        val marginX = 0.15 * bars.width
        assertTrue(bounds.x <= bars.x + slack && bounds.x >= bars.x - marginX, message)
        assertTrue(bounds.right >= bars.right - slack && bounds.right <= bars.right + marginX, message)
        val covered = minOf(bounds.bottom, bars.bottom) - maxOf(bounds.y, bars.y)
        assertTrue(covered >= 0.9 * bars.height, message)
        assertTrue(bounds.y >= bars.y - 0.1 * bars.height && bounds.bottom <= bars.bottom + 0.1 * bars.height, message)
    }

    private fun readOne(
        image: ArgbImage,
        format: CodeFormat,
        text: String,
    ): Box {
        val codes = Reader.read(image)
        assertEquals(listOf(format to text), codes.map { it.format to it.text })
        return codes.single().bounds
    }
}
