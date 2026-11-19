package dev.sluide.git.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.function.ThrowingSupplier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration.ofSeconds
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The lines [GitCommandLog] writes for a command, called directly with the values git's run would give. */
class GitCommandLogEntryTest {
    private val lines = CopyOnWriteArrayList<Pair<System.Logger.Level, String>>()
    private val previous = GitCommandLogSink.sink

    @BeforeEach
    fun capture() {
        GitCommandLogSink.sink = { level, message, _ -> lines += level to message }
    }

    @AfterEach
    fun restore() {
        GitCommandLogSink.sink = previous
    }

    private fun entry(write: Boolean = false) = GitCommandLog.Entry(7, "marker-args", write)

    private val bytes = ByteArray(0)

    private fun text(vararg levels: System.Logger.Level) = lines.filter { it.first in levels }.map { it.second }

    @Test
    fun `the start line has the directory and the size of the input where there are some`() {
        GitCommandLog.start(listOf("a", "b c"), Path.of("/some/dir"), 0, write = false)
        GitCommandLog.start(listOf("a"), null, null, write = true)

        val (with, without) = lines.map { it.second }
        assertTrue(Regex("git#\\d+ start \\[.*] a b c \\(in /some/dir\\) \\(stdin 0 bytes\\)").matches(with), with)
        assertTrue(Regex("git#\\d+ start \\[.*] a").matches(without), without)
    }

    @Test
    fun `a command on another thread starts as information, on the JavaFX thread as a warning`() {
        GitCommandLog.start(listOf("a"), null, null, write = false)
        Thread
            .ofPlatform()
            .name("JavaFX Application Thread")
            .start {
                GitCommandLog.start(listOf("b"), null, null, write = false)
            }.join()

        assertEquals(listOf(System.Logger.Level.INFO, System.Logger.Level.WARNING), lines.map { it.first })
        assertTrue(!lines[0].second.endsWith("on the JavaFX thread"))
        assertTrue(lines[1].second.endsWith(" b on the JavaFX thread"), lines[1].second)
    }

    private fun keep(
        vararg chunks: Int,
        keptSoFar: Int = 0,
    ): Int {
        // A stream that gives the chunks one read each.
        val stream =
            object : InputStream() {
                private var next = 0

                override fun read() = error("read() is not used")

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (next == chunks.size) return -1
                    val count = chunks[next++]
                    for (index in 0 until count) buffer[offset + index] = 'e'.code.toByte()
                    return count
                }
            }
        val into = ByteArrayOutputStream()
        repeat(keptSoFar) { into.write('p'.code) }
        assertTimeoutPreemptively(ofSeconds(10), ThrowingSupplier { GitCommandLog.keepStart(stream, into) })
        return into.size() - keptSoFar
    }

    @Test
    fun `keepStart keeps the first 4096 bytes of what comes in any chunks, and reads the rest`() {
        assertEquals(0, keep())
        assertEquals(1, keep(1))
        assertEquals(4095, keep(4095))
        assertEquals(4096, keep(4096))
        assertEquals(4096, keep(4097))
        assertEquals(4096, keep(3000, 3000))
        assertEquals(4096, keep(3000, 1096, 5000, 1))
        assertEquals(4096, keep(8192, 8192))
        assertEquals(4096, keep(100, 100, 8000))
    }

    @Test
    fun `keepStart gives the kept bytes as they came`() {
        val into = ByteArrayOutputStream()

        GitCommandLog.keepStart(ByteArrayInputStream(ByteArray(10_000) { (it % 251).toByte() }), into)

        assertEquals(ByteArray(STDERR_KEEP) { (it % 251).toByte() }.toList(), into.toByteArray().toList())
    }

    @Test
    fun `a thread is shown by its name, an unnamed one by its description`() {
        val named = Thread.ofPlatform().name("worker-1").unstarted {}
        val unnamed = Thread.ofVirtual().unstarted {}

        assertEquals("worker-1", GitCommandLog.threadLabel(named))
        assertEquals(unnamed.toString(), GitCommandLog.threadLabel(unnamed))
        assertTrue(unnamed.toString().startsWith("VirtualThread"))
    }

    @Test
    fun `text is cut after the given number of characters with an ellipsis, not before`() {
        assertEquals("", GitCommandLog.truncate("", 0))
        assertEquals("abc", GitCommandLog.truncate("abc", 3))
        assertEquals("ab…", GitCommandLog.truncate("abc", 2))
        assertEquals("abc", GitCommandLog.truncate("abc", 10))
    }

    @Test
    fun `a size is shown in bytes below a kilobyte, above in kilobytes with one digit`() {
        assertEquals("0 bytes", GitCommandLog.size(0))
        assertEquals("1023 bytes", GitCommandLog.size(1023))
        assertEquals("1.0 KB", GitCommandLog.size(1024))
        assertEquals("1.5 KB", GitCommandLog.size(1536))
        assertEquals("100.0 KB", GitCommandLog.size(102_400))
    }

    @Test
    fun `a message is one line of its non-blank lines, trimmed, with credentials hidden, cut to 500`() {
        assertEquals("first | second | third", GitCommandLog.message("  first \n\n \t\n second\r\n\n   third  \n"))
        assertEquals("a", GitCommandLog.message("\n a \n"))
        assertEquals("", GitCommandLog.message(" \n\t\n"))
        assertEquals("see https://***@h/x", GitCommandLog.message("see https://u:p@h/x"))
        assertEquals("y".repeat(500) + "…", GitCommandLog.message("y".repeat(501)))
        assertEquals("y".repeat(500), GitCommandLog.message("y".repeat(500)))
    }

    @Test
    fun `the end line says how long the command took, the milliseconds since its start`() {
        val entry = entry()
        val deadline = System.nanoTime() + 60_000_000
        while (System.nanoTime() < deadline) Thread.onSpinWait()

        entry.exited(0, bytes, bytes, complete = true, overflowed = false)

        val millis = Regex("git#7 exit 0 in (\\d+) ms, 0 bytes").matchEntire(lines.single().second)?.groupValues?.get(1)
        assertTrue(millis != null && millis.toLong() in 60..30_000, lines.single().second)
    }

    @Test
    fun `every warning says how long the command ran`() {
        val entry = entry()
        val deadline = System.nanoTime() + 60_000_000
        while (System.nanoTime() < deadline) Thread.onSpinWait()

        entry.timedOut(250.milliseconds)

        val line = lines.single().second
        val millis = Regex("git#7 took longer than 250ms: marker-args \\(after (\\d+) ms\\)").matchEntire(line)
        assertTrue(millis != null && millis.groupValues[1].toLong() in 60..30_000, line)
    }

    @Test
    fun `a command that could not be started is a warning with the error`() {
        val errors = CopyOnWriteArrayList<Throwable?>()
        GitCommandLogSink.sink = { level, message, error ->
            lines += level to message
            errors += error
        }
        val failure = java.io.IOException("no such file")

        entry().startFailed(failure)

        assertEquals(System.Logger.Level.WARNING, lines.single().first)
        assertTrue(
            lines.single().second.startsWith("git#7 could not be started: marker-args (after "),
            lines.single().second,
        )
        assertEquals(listOf<Throwable?>(failure), errors)
    }

    @Test
    fun `a read that failed has its standard error in the end line unless that is blank`() {
        entry().exited(1, bytes, "  \n\t".toByteArray(), complete = true, overflowed = false)
        entry().exited(1, bytes, "fatal: x\n\nmore".toByteArray(), complete = true, overflowed = false)
        entry().exited(0, bytes, "noise".toByteArray(), complete = true, overflowed = false)

        val (blank, said, fine) = lines.map { it.second }
        assertTrue(Regex("git#7 exit 1 in \\d+ ms, 0 bytes").matches(blank), blank)
        assertTrue(Regex("git#7 exit 1 in \\d+ ms, 0 bytes stderr: fatal: x \\| more").matches(said), said)
        assertTrue(Regex("git#7 exit 0 in \\d+ ms, 0 bytes").matches(fine), fine)
        assertEquals(emptyList(), text(System.Logger.Level.WARNING))
    }

    @Test
    fun `a write that failed is a warning with git's message, its output when its standard error is blank`() {
        entry(
            write = true,
        ).exited(2, "out text".toByteArray(), " \n".toByteArray(), complete = true, overflowed = false)
        entry(
            write = true,
        ).exited(2, "out".toByteArray(), "err text".toByteArray(), complete = true, overflowed = false)
        entry(write = true).exited(2, bytes, " ".toByteArray(), complete = true, overflowed = false)
        entry(write = true).exited(0, "out".toByteArray(), "err".toByteArray(), complete = true, overflowed = false)

        val warnings = text(System.Logger.Level.WARNING)
        assertEquals(3, warnings.size, warnings.toString())
        assertTrue(warnings[0].startsWith("git#7 failed with exit 2: out text: marker-args"), warnings[0])
        assertTrue(warnings[1].startsWith("git#7 failed with exit 2: err text: marker-args"), warnings[1])
        assertTrue(warnings[2].startsWith("git#7 failed with exit 2: marker-args"), warnings[2])
        assertTrue(
            lines.none { "stderr:" in it.second },
            "a write's standard error is in its warning, not the end line",
        )
    }

    @Test
    fun `output that was cut is one warning, for the limit or for an unfinished read`() {
        entry().exited(0, bytes, bytes, complete = false, overflowed = true)
        entry().exited(0, bytes, bytes, complete = false, overflowed = false)
        entry().exited(0, bytes, bytes, complete = true, overflowed = false)

        val warnings = text(System.Logger.Level.WARNING)
        assertEquals(2, warnings.size, warnings.toString())
        assertTrue(warnings[0].startsWith("git#7 wrote more output than allowed: "), warnings[0])
        assertTrue(warnings[1].startsWith("git#7 output was not read to the end: "), warnings[1])
    }
}
