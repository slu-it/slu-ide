package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ClipboardImage
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardImagesTest {
    @Test
    fun `the PNG keeps size and pixels, also a transparent and a half transparent one`() {
        val pixels =
            intArrayOf(0xFFFF0000.toInt(), 0x00000000, 0x80336699.toInt(), 0xFF00FF00.toInt(), 0, 0xFF000000.toInt())
        val bytes = pngBytes(ClipboardImage(3, 2, pixels))

        val decoded = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
        val read = IntArray(6).also { decoded.getRGB(0, 0, 3, 2, it, 0, 3) }
        assertEquals(pixels.toList(), read.toList())
    }

    @Test
    fun `the default name has the local time of the clock`() {
        val clock = Clock.fixed(Instant.parse("2026-03-04T23:06:07Z"), ZoneId.of("Asia/Tokyo"))

        assertEquals("image-2026-03-05-080607.png", defaultImageName(clock))
    }

    @ParameterizedTest
    @CsvSource(
        "a,a.png",
        "a.png,a.png",
        "A.PNG,A.PNG",
        "shot.jpg,shot.jpg.png",
        "sub/a,sub/a.png",
        ".png,.png",
        "a.pn,a.pn.png",
    )
    fun `png is appended unless the name ends with it in any case`(
        name: String,
        expected: String,
    ) {
        assertEquals(expected, withPngExtension(name))
    }
}
