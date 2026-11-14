package dev.sluide.imagelimits

import dev.sluide.imagelimits.TestImages.gif
import dev.sluide.imagelimits.TestImages.jpeg
import dev.sluide.imagelimits.TestImages.pngHeader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

/** The caps, their wording and the bounded file read. */
class ImageLimitsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `an image of exactly 40 megapixels fits, one pixel more is too large`() {
        assertIs<ImageCheck.Fits>(ImageLimits.check(pngHeader(1, 40_000_000)))
        assertEquals(
            ImageCheck.TooLarge(checkNotNull(ImageHeaders.read(pngHeader(1, 40_000_001))), ImageLimits.MAX_PIXELS),
            ImageLimits.check(pngHeader(1, 40_000_001)),
        )
    }

    @Test
    fun `a baseline JPEG may have 100 megapixels, a progressive one 40`() {
        assertIs<ImageCheck.Fits>(ImageLimits.check(jpeg(10_000, 10_000)))
        val over = assertIs<ImageCheck.TooLarge>(ImageLimits.check(jpeg(10_000, 10_001)))
        assertEquals(ImageLimits.MAX_JPEG_PIXELS, over.limitPixels)
        val progressive = assertIs<ImageCheck.TooLarge>(ImageLimits.check(jpeg(10_000, 10_000, 0xC2)))
        assertEquals(ImageLimits.MAX_PIXELS, progressive.limitPixels)
    }

    @Test
    fun `the frames of an animated image count together`() {
        // 1 000 × 1 000 pixels per frame: 40 frames are exactly the cap.
        assertIs<ImageCheck.Fits>(ImageLimits.check(gif(1_000, 1_000, 40)))
        val over = assertIs<ImageCheck.TooLarge>(ImageLimits.check(gif(1_000, 1_000, 41)))
        assertEquals(41, over.header.frames)
    }

    @Test
    fun `a header that cannot be read is refused`() {
        assertEquals(ImageCheck.Unreadable, ImageLimits.check("not an image".toByteArray()))
        assertEquals(ImageCheck.Unreadable, ImageLimits.check(ByteArray(0)))
    }

    @Test
    fun `the size is worded with grouped thousands and the frames`() {
        val still = checkNotNull(ImageHeaders.read(pngHeader(30_000, 30_000)))
        val animated = checkNotNull(ImageHeaders.read(gif(4_000, 4_000, 60)))
        assertEquals("30 000 × 30 000 pixels", ImageLimits.sizeText(still))
        assertEquals("60 frames of 4 000 × 4 000 pixels", ImageLimits.sizeText(animated))
        assertEquals("60 frames of 4 000 × 4 000 px", ImageLimits.sizeText(animated, "px"))
        assertEquals("1 × 999 pixels", ImageLimits.sizeText(checkNotNull(ImageHeaders.read(pngHeader(1, 999)))))
    }

    @Test
    fun `a regular file up to the limit is read whole, one byte more is refused`() {
        val atLimit = dir.resolve("ten").also { Files.write(it, ByteArray(10) { i -> i.toByte() }) }
        val over = dir.resolve("eleven").also { Files.write(it, ByteArray(11)) }
        assertContentEquals(ByteArray(10) { i -> i.toByte() }, ImageLimits.readFile(atLimit, maxBytes = 10))
        assertNull(ImageLimits.readFile(over, maxBytes = 10))
        assertContentEquals(ByteArray(11), ImageLimits.readFile(over))
    }

    @Test
    fun `a file larger than any array is cut at the limit, not read whole`() {
        // Sparse, so it takes no disk space; reading it whole would fail with an OutOfMemoryError.
        val huge = dir.resolve("huge")
        RandomAccessFile(huge.toFile(), "rw").use { it.setLength(3L * 1024 * 1024 * 1024) }
        val bytes =
            try {
                ImageLimits.readFile(huge, maxBytes = 1024)
            } catch (_: OutOfMemoryError) {
                // Caught here: JUnit would rethrow it and end the whole test run.
                fail("The file was read whole")
            }
        assertNull(bytes)
    }

    @Test
    fun `a folder and a missing file are not read`() {
        assertNull(ImageLimits.readFile(dir))
        assertNull(ImageLimits.readFile(dir.resolve("missing.png")))
    }

    @Test
    fun `a pipe is not read, so it cannot block or stream without end`() {
        val fifo = dir.resolve("pipe")
        val made =
            runCatching {
                val mkfifo = ProcessBuilder("mkfifo", fifo.toString()).start()
                mkfifo.waitFor(10, TimeUnit.SECONDS) && mkfifo.exitValue() == 0
            }
        assumeTrue(made.getOrNull() == true, "mkfifo is not available")
        // The writer blocks until a reader opens the pipe: readFile, if it opened it, or the test below.
        val writer = thread(isDaemon = true) { Files.newOutputStream(fifo).use { it.write(byteArrayOf(1, 2, 3)) } }

        assertNull(ImageLimits.readFile(fifo))

        Files.newInputStream(fifo).use { it.readAllBytes() }
        writer.join(10_000)
    }
}
