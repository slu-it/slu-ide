package dev.sluide.codeintelligence.navigation

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** The texts of the preview under a list of places: unsaved editor text first, else the disk read in the background. */
class TargetPreviewTextsTest {
    @TempDir
    lateinit var dir: Path

    private val queued = mutableListOf<Runnable>()
    private val dispatched = mutableListOf<Runnable>()
    private var bound = emptyList<BoundText>()
    private val answers = mutableListOf<String?>()

    private fun texts(maxBytes: Long = 1000) =
        TargetPreviewTexts({ bound }, { queued += it }, { dispatched += it }, maxBytes)

    /** Asks for [file], runs what was queued on the background and then what was dispatched; the answers. */
    private fun ask(
        file: Path,
        maxBytes: Long = 1000,
    ): List<String?> {
        answers.clear()
        texts(maxBytes).load(file) { answers += it }
        queued.toList().also { queued.clear() }.forEach { it.run() }
        dispatched.toList().also { dispatched.clear() }.forEach { it.run() }
        return answers.toList()
    }

    @Test
    fun `the unsaved text of a bound editor wins over the disk and is read without the background thread`() {
        val file = dir.resolve("a.py").also { it.writeText("disk") }
        bound = listOf(BoundText(file) { "unsaved" })
        texts().load(file) { answers += it }
        assertEquals(emptyList(), queued, "no background read")
        assertEquals(emptyList(), answers, "answered through dispatch")
        dispatched.single().run()
        assertEquals(listOf<String?>("unsaved"), answers)
    }

    @Test
    fun `an editor's file is found through a path that is not normalised`() {
        dir.resolve("a").createDirectories()
        val file = dir.resolve("b.py").also { it.writeText("disk") }
        bound = listOf(BoundText(dir.resolve("a/../b.py")) { "unsaved" })
        assertEquals(listOf<String?>("unsaved"), ask(file))
        bound = listOf(BoundText(file) { "unsaved" })
        assertEquals(listOf<String?>("unsaved"), ask(dir.resolve("a/../b.py")))
    }

    @Test
    fun `the disk is read on the executor and the answer comes through dispatch`() {
        val file = dir.resolve("a.py").also { it.writeText("print(1)\n") }
        bound = listOf(BoundText(dir.resolve("other.py")) { "other" })
        texts().load(file) { answers += it }
        assertEquals(1, queued.size, "the read waits for the executor")
        assertEquals(emptyList(), dispatched)
        queued.single().run()
        assertEquals(emptyList(), answers, "not answered on the reading thread")
        dispatched.single().run()
        assertEquals(listOf<String?>("print(1)\n"), answers)
    }

    @Test
    fun `a missing file, a folder and a file over the limit give no text`() {
        assertEquals(listOf<String?>(null), ask(dir.resolve("missing.py")))
        assertEquals(listOf<String?>(null), ask(dir.resolve("a").also { it.createDirectories() }))
        val big = dir.resolve("big.py").also { it.writeText("x".repeat(11)) }
        assertEquals(listOf<String?>(null), ask(big, maxBytes = 10))
        assertEquals(listOf<String?>("x".repeat(11)), ask(big, maxBytes = 11))
    }

    @Test
    fun `malformed UTF-8 is replaced`() {
        val file = dir.resolve("a.py").also { it.writeBytes(byteArrayOf(0x61, 0xFF.toByte(), 0x62)) }
        assertEquals(listOf<String?>("a�b"), ask(file))
    }

    @Test
    fun `a read queued before a newer load is skipped, and answers no text`() {
        val first = dir.resolve("first.py").also { it.writeText("first") }
        val second = dir.resolve("second.py").also { it.writeText("second") }
        val loader = texts()
        loader.load(first) { answers += it }
        loader.load(second) { answers += it }
        assertEquals(2, queued.size)
        queued.toList().also { queued.clear() }.forEach { it.run() }
        dispatched.toList().also { dispatched.clear() }.forEach { it.run() }
        // `first.py` exists: only skipping the read answers null for it.
        assertEquals(listOf<String?>(null, "second"), answers.toList())
    }

    @Test
    fun `a device that is no regular file gives no text instead of an empty one`() {
        // POSIX only: /dev/null reads as empty, and a FIFO would block the shared reading thread.
        assumeFalse("win" in System.getProperty("os.name").lowercase())
        assertEquals(listOf<String?>(null), ask(Path.of("/dev/null")))
    }

    @Test
    fun `a limit beyond the range of an int still reads a small file`() {
        val file = dir.resolve("a.py").also { it.writeText("small") }
        assertEquals(listOf<String?>("small"), ask(file, maxBytes = Long.MAX_VALUE))
    }
}
