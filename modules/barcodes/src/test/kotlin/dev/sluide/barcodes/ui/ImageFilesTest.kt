package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.Export
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.Generated
import dev.sluide.barcodes.engine.Generator
import dev.sluide.barcodes.engine.Reader
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The size rule and the file read; JavaFX's `Image` decodes without a scene or a started toolkit. */
class ImageFilesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `an image up to 4096 on the longer side is decoded as it is`() {
        assertNull(decodeSize(4096, 4096))
        assertNull(decodeSize(100, 4000))
        assertNull(decodeSize(1, 1))
    }

    @Test
    fun `a larger image is scaled down to fit 4096 with its aspect ratio`() {
        assertEquals(4096 to 2731, decodeSize(6000, 4000))
        assertEquals(1 to 4096, decodeSize(5, 20_000))
        assertEquals(4096 to 4096, decodeSize(8192, 8192))
    }

    @Test
    fun `a PNG file reads back with its code`() {
        val code = Generator.generate("from a file", CodeFormat.QR_CODE, GenerateOptions()) as Generated.Code
        val png = dir.resolve("code.png")
        Files.write(png, Export.png(code, GenerateOptions(sizePx = 300)))

        val loaded = assertIs<ImageLoad.Loaded>(loadImageFile(png))

        assertEquals(loaded.image.width, loaded.image.height)
        assertEquals("from a file", Reader.read(loaded.image).single().text)
    }

    @Test
    fun `a 6000 by 4000 PNG is decoded no larger than 4096 on the longer side`() {
        val png = dir.resolve("big.png")
        Files.write(png, grayPng(6000, 4000))

        val loaded = assertIs<ImageLoad.Loaded>(loadImageFile(png))

        assertEquals(4096 to 2731, loaded.image.width to loaded.image.height)
    }

    @Test
    fun `a PNG with big pixel dimensions is refused by its header before any decoding`() {
        // 400 megapixels of IDAT data (a real bomb: it deflates to a few hundred KB): decoding it would need 1.6 GB.
        val bomb = dir.resolve("bomb.png").also { Files.write(it, grayPng(20_000, 20_000)) }
        assertEquals(
            ImageLoad.Failed(
                "The image is too large to read (20 000 × 20 000 pixels; at most 40 megapixels).",
            ),
            loadImageFile(bomb),
        )
    }

    @Test
    fun `a GIF and a BMP with large headers are refused the same way`() {
        val gif = dir.resolve("bomb.gif").also { Files.write(it, gif(20_000, 20_000, 1)) }
        val bmp = dir.resolve("bomb.bmp").also { Files.write(it, bmpHeader(12_000, 12_000)) }
        assertEquals(
            ImageLoad.Failed("The image is too large to read (20 000 × 20 000 pixels; at most 40 megapixels)."),
            loadImageFile(gif),
        )
        assertEquals(
            ImageLoad.Failed("The image is too large to read (12 000 × 12 000 pixels; at most 40 megapixels)."),
            loadImageFile(bmp),
        )
    }

    private fun jpeg(
        width: Int,
        height: Int,
        marker: Int = 0xC0,
    ) = intArrayOf(0xFF, 0xD8, 0xFF, marker, 0, 11, 8, height shr 8, height and 0xFF, width shr 8, width and 0xFF, 1)
        .map { it.toByte() }
        .toByteArray()

    @Test
    fun `a baseline JPEG is refused over 100 megapixels, a progressive one over 40`() {
        val bomb = dir.resolve("bomb.jpg").also { Files.write(it, jpeg(20_000, 20_000)) }
        assertEquals(
            ImageLoad.Failed("The image is too large to read (20 000 × 20 000 pixels; at most 100 megapixels)."),
            loadImageFile(bomb),
        )
        // 7 746² is 60 megapixels: baseline passes the size check (then fails as it is no real JPEG), progressive not.
        val baseline = dir.resolve("baseline.jpg").also { Files.write(it, jpeg(7_746, 7_746)) }
        assertEquals(ImageLoad.Failed(UNREADABLE), loadImageFile(baseline))
        for (marker in listOf(0xC2, 0xC6, 0xCA, 0xCE)) {
            val progressive = dir.resolve("progressive$marker.jpg").also { Files.write(it, jpeg(7_746, 7_746, marker)) }
            assertEquals(
                ImageLoad.Failed("The image is too large to read (7 746 × 7 746 pixels; at most 40 megapixels)."),
                loadImageFile(progressive),
            )
        }
    }

    @Test
    fun `an animated GIF counts all its frames against the limit`() {
        val animated = dir.resolve("anim.gif").also { Files.write(it, gif(4_000, 4_000, 30)) }
        assertEquals(
            ImageLoad.Failed(
                "The image is too large to read (30 frames of 4 000 × 4 000 pixels; at most 40 megapixels).",
            ),
            loadImageFile(animated),
        )
    }

    @Test
    fun `a single-frame GIF loads, a small animated one is refused as animated`() {
        val one = dir.resolve("one.gif").also { Files.write(it, gif(1, 1, 1)) }
        val three = dir.resolve("three.gif").also { Files.write(it, gif(1, 1, 3)) }
        assertEquals(1 to 1, assertIs<ImageLoad.Loaded>(loadImageFile(one)).image.let { it.width to it.height })
        // JavaFX has no pixels for an animated image, so it is refused from the frame count, not decoded to fail.
        assertEquals(ImageLoad.Failed(ANIMATED), loadImageFile(three))
        assertEquals(3, checkNotNull(imageHeader(gif(1, 1, 3))).frames)
    }

    @Test
    fun `a GIF whose block chain is cut off or has no trailer is unreadable`() {
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
            val file = dir.resolve("cut$index.gif").also { Files.write(it, bytes) }
            assertEquals(ImageLoad.Failed(UNREADABLE), loadImageFile(file), "cut $index")
        }
    }

    @Test
    fun `a PNG of 8K by 4K, 33 megapixels, is accepted`() {
        val png = dir.resolve("8k.png").also { Files.write(it, grayPng(7680, 4320)) }
        val loaded = assertIs<ImageLoad.Loaded>(loadImageFile(png))
        assertEquals(4096 to 2304, loaded.image.width to loaded.image.height)
    }

    @Test
    fun `a PNG cut off in its data is unreadable`() {
        val whole = grayPng(300, 300)
        val cut = dir.resolve("cut-data.png").also { Files.write(it, whole.copyOf(whole.size / 2)) }
        assertEquals(ImageLoad.Failed(UNREADABLE), loadImageFile(cut))
    }

    @Test
    fun `a file over 20 MiB is refused without being decoded`() {
        val big = dir.resolve("big.png")
        Files.write(big, ByteArray(20 * 1024 * 1024 + 1))
        assertEquals(ImageLoad.Failed(TOO_LARGE), loadImageFile(big))
    }

    @Test
    fun `corrupt, empty, missing and truncated files are unreadable`() {
        val corrupt =
            dir
                .resolve(
                    "corrupt.png",
                ).also { Files.write(it, "not an image at all, just text".toByteArray()) }
        val empty = dir.resolve("empty.png").also { Files.write(it, ByteArray(0)) }
        val truncated = dir.resolve("cut.png").also { Files.write(it, grayPng(50, 50).copyOf(60)) }
        for (file in listOf(corrupt, empty, truncated, dir.resolve("missing.png"), dir)) {
            assertEquals(ImageLoad.Failed(UNREADABLE), loadImageFile(file), file.toString())
        }
    }

    @Test
    fun `the header gives the size of PNG, GIF, BMP and JPEG`() {
        assertEquals(6000 to 4000, imageDimensions(grayPng(6000, 4000)))
        assertEquals(300 to 144, imageDimensions(gif(300, 144, 1)))
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
        assertEquals(100 to 50, imageDimensions(bmp))
        // SOI, an APP0 segment of 4 bytes, then SOF0: height 480, width 640.
        val jpeg =
            intArrayOf(0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0, 0xFF, 0xC0, 0, 11, 8, 0x01, 0xE0, 0x02, 0x80, 1)
                .map { it.toByte() }
                .toByteArray()
        assertEquals(640 to 480, imageDimensions(jpeg))
        assertNull(imageDimensions(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(imageDimensions("hello".toByteArray()))
    }

    @Test
    fun `a gigapixel header is read without decoding anything`() {
        val size = imageDimensions(pngHeader(100_000, 100_000))
        assertEquals(100_000 to 100_000, size)
        assertTrue(decodeSize(100_000, 100_000) == 4096 to 4096)
    }

    /** An 8-bit grayscale PNG of one flat grey, written by hand: tiny however large it is. */
    private fun grayPng(
        width: Int,
        height: Int,
    ): ByteArray {
        val raw = ByteArray((width + 1) * height) { 0x80.toByte() }
        for (row in 0 until height) raw[row * (width + 1)] = 0
        val deflated = ByteArrayOutputStream()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) deflated.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))

        fun chunk(
            type: String,
            data: ByteArray,
        ) {
            out.write(intBytes(data.size))
            val body = type.toByteArray() + data
            out.write(body)
            out.write(intBytes(CRC32().also { it.update(body) }.value.toInt()))
        }
        chunk("IHDR", intBytes(width) + intBytes(height) + byteArrayOf(8, 0, 0, 0, 0))
        chunk("IDAT", deflated.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * A GIF with a [width] × [height] screen and [frames] tiny frames (each a valid 1 × 1 image at the corner), so
     * its block chain is real: a global colour table, one image descriptor with its data per frame, the trailer.
     */
    private fun gif(
        width: Int,
        height: Int,
        frames: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(width.toByte(), (width shr 8).toByte(), height.toByte(), (height shr 8).toByte()))
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1))
        repeat(frames) {
            // A graphic control extension, then the image descriptor and its data.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0))
            out.write(byteArrayOf(2, 2, 0x44, 1, 0))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun bmpHeader(
        width: Int,
        height: Int,
    ) = ByteArray(30).also {
        it[0] = 'B'.code.toByte()
        it[1] = 'M'.code.toByte()
        it[14] = 40
        for (i in 0..3) {
            it[18 + i] = (width shr (8 * i)).toByte()
            it[22 + i] = (height shr (8 * i)).toByte()
        }
    }

    /** The signature and IHDR of a PNG of any claimed size, nothing else. */
    private fun pngHeader(
        width: Int,
        height: Int,
    ) = grayPng(1, 1).copyOf(16) + intBytes(width) + intBytes(height) + byteArrayOf(8, 0, 0, 0, 0)

    private fun intBytes(value: Int) =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
