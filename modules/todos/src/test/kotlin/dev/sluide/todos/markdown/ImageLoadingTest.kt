package dev.sluide.todos.markdown

import dev.sluide.imagelimits.ImageLimits
import dev.sluide.imagelimits.TestImages
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImageLoadingTest {
    @TempDir
    lateinit var dir: Path

    private val noFetch = ImageFetcher { error("no fetch expected") }

    private fun local(
        name: String,
        bytes: ByteArray,
    ): String = Files.write(dir.resolve(name), bytes).toUri().toString()

    private fun refusedReason(result: ImageBytes) = assertIs<ImageBytes.Refused>(result).reason

    @Test
    fun `a local bomb is refused with its size`() {
        val source = local("bomb.png", TestImages.pngHeader(30_000, 30_000))
        assertEquals("too large to show (30 000 × 30 000 px)", refusedReason(loadImageBytes(source, noFetch)))
    }

    @Test
    fun `a local normal image is loaded`() {
        val bytes = TestImages.grayPng(4, 4)
        val loaded = assertIs<ImageBytes.Loaded>(loadImageBytes(local("ok.png", bytes), noFetch))
        assertTrue(loaded.bytes.contentEquals(bytes))
    }

    @Test
    fun `a local file over 20 MiB is not read`() {
        val path = dir.resolve("big.png")
        Files.write(path, TestImages.grayPng(1, 1))
        // Sparse where the file system allows it: one byte written past the limit.
        FileChannel.open(path, StandardOpenOption.WRITE).use {
            it.write(ByteBuffer.wrap(byteArrayOf(0)), ImageLimits.MAX_FILE_BYTES.toLong())
        }
        // ImageLimits.readFile answers null for an over-long file, so the reason is the general one.
        assertEquals("could not be loaded", refusedReason(loadImageBytes(path.toUri().toString(), noFetch)))
    }

    @Test
    fun `a web body over the limit is refused`() {
        val fetcher = ImageFetcher { ByteArray(ImageLimits.MAX_FILE_BYTES + 1) }
        assertEquals("larger than 20 MiB", refusedReason(loadImageBytes("https://example.com/a.png", fetcher)))
    }

    @Test
    fun `a failed web fetch is could not be loaded`() {
        assertEquals(
            "could not be loaded",
            refusedReason(loadImageBytes("https://example.com/a.png", ImageFetcher { null })),
        )
    }

    @Test
    fun `a web image gets the parsed uri and is checked like a file`() {
        var asked: URI? = null
        val fetcher =
            ImageFetcher {
                asked = it
                TestImages.pngHeader(30_000, 30_000)
            }
        assertEquals(
            "too large to show (30 000 × 30 000 px)",
            refusedReason(loadImageBytes("https://example.com/a.png", fetcher)),
        )
        assertEquals(URI("https://example.com/a.png"), asked)
    }

    @Test
    fun `contexts without their own fetcher share one, so that cards do not each make an HTTP client`() {
        val first = MarkdownContext(dir, {}, {}, null)
        val second = MarkdownContext(dir, {}, {}, null)
        assertSame(first.imageFetcher, second.imageFetcher)
        assertSame(first.imageExecutor, second.imageExecutor)
    }

    @Test
    fun `garbage bytes are could not be read`() {
        val source = local("x.png", "garbage".toByteArray())
        assertEquals("could not be read", refusedReason(loadImageBytes(source, noFetch)))
    }
}
