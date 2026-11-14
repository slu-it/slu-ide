package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.Export
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.Generated
import dev.sluide.barcodes.engine.Generator
import dev.sluide.barcodes.engine.Reader
import dev.sluide.imagelimits.TestImages.bmpHeader
import dev.sluide.imagelimits.TestImages.gif
import dev.sluide.imagelimits.TestImages.grayPng
import dev.sluide.imagelimits.TestImages.jpeg
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
        assertEquals(4096 to 4096, decodeSize(100_000, 100_000))
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
}
