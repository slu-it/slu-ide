package dev.sluide.imagelimits

import dev.sluide.imagelimits.TestImages.IcoEntry
import dev.sluide.imagelimits.TestImages.animatedWebp
import dev.sluide.imagelimits.TestImages.bmpHeader
import dev.sluide.imagelimits.TestImages.coreDib
import dev.sluide.imagelimits.TestImages.dib
import dev.sluide.imagelimits.TestImages.gif
import dev.sluide.imagelimits.TestImages.grayPng
import dev.sluide.imagelimits.TestImages.ico
import dev.sluide.imagelimits.TestImages.jpeg
import dev.sluide.imagelimits.TestImages.pngHeader
import dev.sluide.imagelimits.TestImages.riffChunk
import dev.sluide.imagelimits.TestImages.vp8
import dev.sluide.imagelimits.TestImages.vp8l
import dev.sluide.imagelimits.TestImages.vp8x
import dev.sluide.imagelimits.TestImages.webp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The header reader on real and hostile headers: sizes, the GIF block walk, the JPEG segment walk, the WebP chunk
 * walk, the ICO directory, and random mutations of each.
 */
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

    @Test
    fun `a lossy, a lossless and an extended WebP give their size`() {
        assertEquals(ImageHeader(640, 480, 1, ImageFormat.WEBP, false), ImageHeaders.read(webp(vp8(640, 480))))
        assertEquals(16_383 to 3, dimensions(webp(vp8(16_383, 3))))
        assertEquals(16_384 to 1, dimensions(webp(vp8l(16_384, 1))))
        assertEquals(1 to 16_384, dimensions(webp(vp8l(1, 16_384))))
        val still = checkNotNull(ImageHeaders.read(webp(vp8x(30_000, 30_000, animated = false), vp8l(1, 1))))
        assertEquals(30_000 to 30_000, still.width to still.height)
        assertEquals(1, still.frames)
        assertEquals(16_777_216 to 1, dimensions(webp(vp8x(16_777_216, 1, animated = false))))
    }

    @Test
    fun `an extended still WebP takes the larger of its canvas and its image`() {
        assertEquals(16_384 to 20, dimensions(webp(vp8x(10, 20, animated = false), vp8l(16_384, 16))))
        assertEquals(30 to 16_000, dimensions(webp(vp8x(30, 10, animated = false), vp8(16, 16_000))))
        assertNull(ImageHeaders.read(webp(vp8x(10, 10, animated = false), riffChunk("VP8L", byteArrayOf(0x2E)))))
    }

    @Test
    fun `an image chunk too short for its size is no header, not read from the next chunk`() {
        // Each short chunk is followed by one whose header bytes would give a size if read as the short one's.
        val next = riffChunk("ABCD", ByteArray(4))
        val lossy = riffChunk("VP8 ", byteArrayOf(0x10, 0x02, 0x00, 0x9D.toByte(), 0x01, 0x2A))
        val lossless = riffChunk("VP8L", byteArrayOf(0x2F))
        val extended = riffChunk("VP8X", ByteArray(4))
        for ((name, chunk) in listOf("VP8 " to lossy, "VP8L" to lossless, "VP8X" to extended)) {
            assertNull(ImageHeaders.read(webp(chunk, next)), name)
        }
    }

    @Test
    fun `an animated WebP counts its ANMF chunks as frames`() {
        val animated = checkNotNull(ImageHeaders.read(animatedWebp(4_000, 4_000, 60)))
        assertEquals(60, animated.frames)
        assertEquals(60L * 4_000 * 4_000, animated.pixels)
        assertEquals(1, ImageHeaders.read(animatedWebp(10, 10, 1))?.frames)
        // ANMF chunks of a still image are not its frames; an animation without any frame is no image.
        val frame = riffChunk("ANMF", ByteArray(16) + vp8l(1, 1))
        assertEquals(1, ImageHeaders.read(webp(vp8x(10, 10, animated = false), frame, frame))?.frames)
        assertNull(ImageHeaders.read(webp(vp8x(10, 10, animated = true))))
    }

    @Test
    fun `a WebP with a zero-size chunk, an odd-size chunk or bytes after its RIFF size is walked`() {
        val frame = riffChunk("ANMF", ByteArray(16) + vp8l(1, 1))
        val empty = riffChunk("EMPT", ByteArray(0))
        val odd = riffChunk("ODD ", ByteArray(3) { 'A'.code.toByte() })
        assertEquals(2, ImageHeaders.read(webp(vp8x(10, 10, animated = true), empty, frame, odd, frame))?.frames)
        // An odd chunk at the very end may lack its pad byte.
        val unpadded = webp(vp8l(5, 5), odd).let { it.copyOf(it.size - 1) }
        assertEquals(5 to 5, dimensions(unpadded.also { setLe32(it, 4, it.size - 8) }))
        // Bytes after the RIFF size are not walked.
        assertEquals(5 to 5, dimensions(webp(vp8l(5, 5)) + "junk".toByteArray()))
    }

    @Test
    fun `a WebP cut short, with a chunk past the end or an unknown first chunk is no header`() {
        val whole = animatedWebp(10, 10, 3)
        // Inside the last frame, inside a chunk header, and right after the RIFF header.
        for (cut in listOf(whole.size - 5, whole.size - 30 - 4, 12, 16)) {
            assertNull(ImageHeaders.read(whole.copyOf(cut)), "cut at $cut")
        }
        // The ANIM chunk (after the 12 RIFF bytes and the 18 of VP8X) claims more than there is.
        for (size in listOf(whole.size, 0x7FFF_FFFF, -1)) {
            assertNull(ImageHeaders.read(whole.copyOf().also { setLe32(it, 12 + 18 + 4, size) }), "size $size")
        }
        assertNull(ImageHeaders.read(webp(riffChunk("VP8 ", ByteArray(0)))))
        assertNull(ImageHeaders.read(webp(riffChunk("JUNK", ByteArray(10)), vp8l(1, 1))))
        assertNull(ImageHeaders.read(webp()))
        assertNull(ImageHeaders.read(webp(vp8(0, 10))))
        assertNull(ImageHeaders.read(webp(vp8l(1, 1)).also { it[20] = 0x2E }))
    }

    @Test
    fun `an icon's BMP entry gives the larger of its directory and DIB size`() {
        assertEquals(
            ImageHeader(32, 32, 1, ImageFormat.ICO, false),
            ImageHeaders.read(ico(IcoEntry(32, 32, dib(32, 32)))),
        )
        // 0 in the directory means 256.
        assertEquals(256 to 256, dimensions(ico(IcoEntry(256, 256, dib(16, 16)))))
        assertEquals(30_000 to 30_000, dimensions(ico(IcoEntry(16, 16, dib(30_000, 30_000)))))
        assertNull(ImageHeaders.read(ico(IcoEntry(16, 16, dib(-1, 16)))))
        assertNull(ImageHeaders.read(ico(IcoEntry(16, 16, ByteArray(11)))))
    }

    @Test
    fun `an icon's BMP entry with the older core header gives its 16-bit size`() {
        assertEquals(16 to 16, dimensions(ico(IcoEntry(16, 16, coreDib(16, 16)))))
        assertEquals(1_000 to 1_000, dimensions(ico(IcoEntry(16, 16, coreDib(1_000, 1_000)))))
    }

    @Test
    fun `an icon's PNG entry too short for its IHDR is no header, not read from the next entry`() {
        // The signature, the IHDR length and type: 16 bytes. The next entry's DIB header would give a positive size.
        val cut = IcoEntry(16, 16, pngHeader(16, 16).copyOf(16))
        assertNull(ImageHeaders.read(ico(cut, IcoEntry(16, 16, dib(16, 16)))))
    }

    @Test
    fun `an icon's PNG entry gives the size of its IHDR, not of the directory`() {
        val header = checkNotNull(ImageHeaders.read(ico(IcoEntry(256, 256, pngHeader(30_000, 30_000)))))
        assertEquals(30_000 to 30_000, header.width to header.height)
        assertIs<ImageCheck.TooLarge>(ImageLimits.check(ico(IcoEntry(256, 256, pngHeader(30_000, 30_000)))))
        assertNull(ImageHeaders.read(ico(IcoEntry(16, 16, pngHeader(16, 16).copyOf(23)))))
        assertNull(ImageHeaders.read(ico(IcoEntry(16, 16, pngHeader(0, 16)))))
    }

    @Test
    fun `an icon counts its entries as frames and the sum of their pixels`() {
        val header =
            checkNotNull(
                ImageHeaders.read(
                    ico(
                        IcoEntry(16, 16, dib(16, 16)),
                        IcoEntry(0, 0, pngHeader(6_000, 6_000)),
                        IcoEntry(48, 48, dib(48, 48)),
                    ),
                ),
            )
        assertEquals(ImageHeader(6_000, 6_000, 3, ImageFormat.ICO, false, 16L * 16 + 6_000L * 6_000 + 48 * 48), header)
    }

    @Test
    fun `an icon with no entry, a cut directory or an entry past the end is no header`() {
        val whole = ico(IcoEntry(16, 16, dib(16, 16)), IcoEntry(32, 32, dib(32, 32)))
        assertNull(ImageHeaders.read(ico()))
        assertNull(ImageHeaders.read(whole.copyOf(6 + 16 * 2 - 1)))
        assertNull(ImageHeaders.read(whole.copyOf(whole.size - 1)))
        // The second entry's offset (at 6 + 16 + 12) and size (at 6 + 16 + 8) point past the end.
        for (offset in listOf(whole.size - 39, whole.size, -1)) {
            assertNull(ImageHeaders.read(whole.copyOf().also { setLe32(it, 6 + 16 + 12, offset) }), "offset $offset")
        }
        assertNull(ImageHeaders.read(whole.copyOf().also { setLe32(it, 6 + 16 + 8, 41) }))
        // A count of 65 535 entries in a file of a few bytes.
        assertNull(ImageHeaders.read(whole.copyOf().also { it[4] = -1 }.also { it[5] = -1 }))
    }

    @Test
    fun `200 000 random mutations of each format never throw`() {
        val formats =
            mapOf(
                "PNG" to grayPng(16, 16),
                "GIF" to gif(16, 16, 3),
                "progressive JPEG" to
                    intArrayOf(0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0).map { it.toByte() }.toByteArray() +
                    jpeg(64, 64, 0xC2).copyOfRange(2, 12),
                "BMP" to bmpHeader(16, 16),
                "animated WebP" to animatedWebp(16, 16, 3),
                "ICO" to ico(IcoEntry(16, 16, dib(16, 16)), IcoEntry(0, 0, pngHeader(300, 300))),
            )
        for ((index, entry) in formats.entries.withIndex()) {
            val (name, valid) = entry
            checkNotNull(ImageHeaders.read(valid)) { "$name is valid" }
            val random = Random(index)
            repeat(200_000) { round ->
                val mutated = mutate(valid, random)
                try {
                    ImageHeaders.read(mutated)
                } catch (e: Exception) {
                    throw AssertionError("$name, round $round: ${mutated.joinToString(" ") { "%02x".format(it) }}", e)
                }
            }
        }
    }

    /** One to four random changes: a byte, a 32-bit field set to an extreme, a cut, bytes appended. */
    private fun mutate(
        valid: ByteArray,
        random: Random,
    ): ByteArray {
        var bytes = valid.copyOf()
        repeat(1 + random.nextInt(4)) {
            when (random.nextInt(4)) {
                0 -> {
                    if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                }

                1 -> {
                    if (bytes.size >= 4) setLe32(bytes, random.nextInt(bytes.size - 3), EXTREMES.random(random))
                }

                2 -> {
                    bytes = bytes.copyOf(random.nextInt(bytes.size + 1))
                }

                else -> {
                    bytes += ByteArray(random.nextInt(17)).also(random::nextBytes)
                }
            }
        }
        return bytes
    }

    private fun setLe32(
        bytes: ByteArray,
        at: Int,
        value: Int,
    ) {
        for (i in 0..3) bytes[at + i] = (value ushr (8 * i)).toByte()
    }

    private companion object {
        val EXTREMES = listOf(0, 1, -1, 0x7FFF_FFFF, Int.MIN_VALUE, 0xFFFF, 0x10000)
    }
}
