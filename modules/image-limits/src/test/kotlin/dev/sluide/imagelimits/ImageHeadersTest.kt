package dev.sluide.imagelimits

import dev.sluide.imagelimits.TestImages.bmpHeader
import dev.sluide.imagelimits.TestImages.gif
import dev.sluide.imagelimits.TestImages.grayPng
import dev.sluide.imagelimits.TestImages.jpeg
import dev.sluide.imagelimits.TestImages.pngHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The header reader on real and hostile headers: sizes, the GIF block walk, the JPEG segment walk. */
class ImageHeadersTest {
    private fun dimensions(bytes: ByteArray) = ImageHeaders.read(bytes)?.let { it.width to it.height }

    @Test
    fun `the header gives the size of PNG, GIF, BMP and JPEG`() {
        assertEquals(6000 to 4000, dimensions(grayPng(6000, 4000)))
        assertEquals(300 to 144, dimensions(gif(300, 144, 1)))
        val bmp =
            ByteArray(30).also {
                it[0] = 'B'.code.toByte()
                it[1] = 'M'.code.toByte()
                it[14] = 40
                it[18] = 100
                it[22] = (-50).toByte()
                it[23] = -1
                it[24] = -1
                it[25] = -1
            }
        assertEquals(100 to 50, dimensions(bmp))
        // SOI, an APP0 segment of 4 bytes, then SOF0: height 480, width 640.
        val jpeg =
            intArrayOf(0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0, 0xFF, 0xC0, 0, 11, 8, 0x01, 0xE0, 0x02, 0x80, 1)
                .map { it.toByte() }
                .toByteArray()
        assertEquals(640 to 480, dimensions(jpeg))
        assertNull(dimensions(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(dimensions("hello".toByteArray()))
        assertNull(dimensions(ByteArray(0)))
    }

    @Test
    fun `the header names its format`() {
        assertEquals(ImageFormat.PNG, ImageHeaders.read(pngHeader(1, 1))?.format)
        assertEquals(ImageFormat.GIF, ImageHeaders.read(gif(1, 1, 1))?.format)
        assertEquals(ImageFormat.BMP, ImageHeaders.read(bmpHeader(1, 1))?.format)
        assertEquals(ImageFormat.JPEG, ImageHeaders.read(jpeg(1, 1))?.format)
    }

    @Test
    fun `a gigapixel header is read without decoding anything`() {
        assertEquals(100_000 to 100_000, dimensions(pngHeader(100_000, 100_000)))
    }

    @Test
    fun `a zero or negative size is no header`() {
        assertNull(ImageHeaders.read(pngHeader(0, 10)))
        assertNull(ImageHeaders.read(pngHeader(10, -1)))
        assertNull(ImageHeaders.read(bmpHeader(Int.MIN_VALUE, 10)))
        assertNull(ImageHeaders.read(bmpHeader(10, Int.MIN_VALUE)))
        assertNull(ImageHeaders.read(gif(0, 10, 1)))
    }

    @Test
    fun `the start-of-frame marker tells baseline from progressive JPEG`() {
        for (marker in listOf(0xC0, 0xC1, 0xC3)) {
            assertTrue(checkNotNull(ImageHeaders.read(jpeg(10, 10, marker))).baselineJpeg, "marker $marker")
        }
        for (marker in listOf(0xC2, 0xC6, 0xCA, 0xCE)) {
            assertFalse(checkNotNull(ImageHeaders.read(jpeg(10, 10, marker))).baselineJpeg, "marker $marker")
        }
        // DHT (C4), JPG (C8) and DAC (CC) are no start-of-frame markers: they are skipped as segments.
        assertNull(ImageHeaders.read(jpeg(10, 10, 0xC4)))
        assertFalse(checkNotNull(ImageHeaders.read(pngHeader(10, 10))).baselineJpeg)
    }

    @Test
    fun `a JPEG cut off inside its start-of-frame segment is no header`() {
        val whole = jpeg(640, 480)
        assertNull(ImageHeaders.read(whole.copyOf(whole.size - 4)))
    }

    @Test
    fun `a GIF counts its frames by walking its blocks`() {
        assertEquals(3, checkNotNull(ImageHeaders.read(gif(1, 1, 3))).frames)
        assertEquals(1, checkNotNull(ImageHeaders.read(gif(1, 1, 1))).frames)
        assertEquals(1, checkNotNull(ImageHeaders.read(pngHeader(1, 1))).frames)
        // A trailer without any frame is no image.
        assertNull(ImageHeaders.read(gif(1, 1, 0)))
    }

    @Test
    fun `a GIF whose block chain is cut off or has no trailer is no header`() {
        val whole = gif(1, 1, 3)
        // Cut inside the last frame's data, after the last frame's data (no trailer), and with a stray block type.
        val cuts =
            listOf(
                whole.copyOf(whole.size - 4),
                whole.copyOf(whole.size - 1),
                whole.copyOf(whole.size - 1) + byteArrayOf(0x55, 0x3B),
                whole.copyOf(14),
            )
        for ((index, bytes) in cuts.withIndex()) {
            assertNull(ImageHeaders.read(bytes), "cut $index")
        }
    }

    @Test
    fun `a GIF sub-block length past the end is no header`() {
        val whole = gif(1, 1, 1)
        // The image data's first sub-block length claims 255 bytes: it follows the 13 header bytes, the colour table
        // of 6, the extension of 8, the image descriptor of 10 and the code size byte.
        val lying = whole.copyOf().also { it[13 + 3 * 2 + 8 + 10 + 1] = 0xFF.toByte() }
        assertNull(ImageHeaders.read(lying))
    }
}
