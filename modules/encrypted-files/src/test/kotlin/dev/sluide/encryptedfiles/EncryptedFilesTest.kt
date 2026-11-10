package dev.sluide.encryptedfiles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

class EncryptedFilesTest {
    private val scheduled = mutableListOf<Pair<Duration, Runnable>>()
    private val files = EncryptedFiles(Duration.ofMinutes(1)) { delay, task -> scheduled += delay to task }
    private val file = Path.of("/scratches/notes.md.sluenc")

    private fun newKey(): FileKey =
        Sluenc.create("long enough password".toCharArray(), ByteArray(0), KdfParams(64, 1, 1)).key

    private fun FileKey.isZeroed() = rawDek.all { it == 0.toByte() }

    @Test
    fun `a handed over key is taken once, by its normalized path`() {
        val key = newKey()
        files.handOver(file, key)

        assertSame(key, files.takeHandover(Path.of("/scratches/sub/../notes.md.sluenc")))
        assertNull(files.takeHandover(file))
        assertFalse(key.isZeroed())
    }

    @Test
    fun `a handover not taken in time is closed, a taken one is not`() {
        val late = newKey()
        files.handOver(file, late)
        val taken = newKey()
        val other = Path.of("/scratches/other.txt.sluenc")
        files.handOver(other, taken)
        files.takeHandover(other)

        assertEquals(listOf(Duration.ofMinutes(1), Duration.ofMinutes(1)), scheduled.map { it.first })
        scheduled.forEach { it.second.run() }

        assertTrue(late.isZeroed())
        assertNull(files.takeHandover(file))
        assertFalse(taken.isZeroed())
    }

    @Test
    fun `a newer handover for the same file closes the older key`() {
        val older = newKey()
        val newer = newKey()
        files.handOver(file, older)
        files.handOver(file, newer)

        assertTrue(older.isZeroed())
        scheduled.first().second.run()
        assertSame(newer, files.takeHandover(file))
        assertFalse(newer.isZeroed())
    }

    @Test
    fun `nothing is unlocked and nothing closed empty at first`() {
        assertFalse(files.anyUnlocked.get())
        assertNull(files.takeClosedEmpty(file))
    }
}
