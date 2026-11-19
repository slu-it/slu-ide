package dev.sluide.findinfiles

import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilePreviewTextsTest {
    @TempDir
    lateinit var dir: Path

    private val background = mutableListOf<Runnable>()
    private val fx = mutableListOf<Runnable>()
    private var unsaved: Map<Path, String> = emptyMap()
    private val texts = FilePreviewTexts({ unsaved }, { background += it }, { fx += it })

    private fun load(file: Path): String? {
        var result: String? = "not called"
        texts.load(file) { result = it }
        background.toList().also { background.clear() }.forEach { it.run() }
        fx.toList().also { fx.clear() }.forEach { it.run() }
        return result
    }

    @Test
    fun `the unsaved buffer wins over the disk and needs no background thread`() {
        val file = dir.resolve("a.txt").apply { writeText("disk") }
        unsaved = mapOf(dir.resolve("x/../a.txt") to "buffer\r\nline")
        var result: String? = null
        texts.load(file) { result = it }

        assertTrue(background.isEmpty())
        fx.single().run()
        assertEquals("buffer\nline", result)
    }

    @Test
    fun `an unsaved buffer over the size limit gives null`() {
        val file = dir.resolve("a.txt").apply { writeText("disk") }
        unsaved = mapOf(file to "a".repeat(2 * 1024 * 1024 + 1))

        assertEquals(null, load(file))
    }

    @Test
    fun `without a buffer the file is read on the background thread`() {
        val file = dir.resolve("a.txt").apply { writeText("one\r\ntwo") }
        var result: String? = null
        texts.load(file) { result = it }

        assertTrue(fx.isEmpty())
        background.single().run()
        fx.single().run()
        assertEquals("one\ntwo", result)
    }

    @Test
    fun `binary, too large and missing files give null`() {
        val binary = dir.resolve("b.bin").apply { writeBytes(ByteArray(100)) }
        val large = dir.resolve("big.txt").apply { writeBytes(ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() }) }

        assertEquals(null, load(binary))
        assertEquals(null, load(large))
        assertEquals(null, load(dir.resolve("missing.txt")))
    }

    @Test
    fun `a read that throws gives null instead of leaving the preview waiting`() {
        val zip = dir.resolve("a.zip")
        val inside =
            FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), mapOf("create" to "true")).use {
                it.getPath("/inside.txt")
            }
        var called = false
        var result: String? = "not null"
        texts.load(inside) {
            called = true
            result = it
        }
        background.toList().also { background.clear() }.forEach { it.run() }
        fx.toList().also { fx.clear() }.forEach { it.run() }

        assertTrue(called)
        assertEquals(null, result)
    }
}
