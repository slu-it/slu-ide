package dev.sluide.fileviewers.ignore

import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreLineEffect
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.textediting.TextEncoding
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IgnoreDotsTrackerTest {
    @TempDir
    lateinit var dir: Path

    private var saved = "# c\nbuild/\n\n*.log\nold\n"
    private var text = saved
    private var service: IgnoreRuleEffects? = IgnoreRuleEffects()
    private var lookups = 0
    private var refreshes = 0
    private var deferred = false
    private val pending = mutableListOf<(IgnoreFileEffects?) -> Unit>()
    private val paths = mutableListOf<Path>()

    // Lines: 0 comment, 1 build/ (active), 2 blank, 3 *.log (tracked only), 4 old (no effect).
    private val answer =
        IgnoreFileEffects(
            mapOf(
                1 to IgnoreLineEffect(1, 0, 0, listOf("build/"), emptyList()),
                3 to IgnoreLineEffect(0, 0, 2, emptyList(), listOf("a.txt", "b.txt")),
            ),
            complete = true,
        )

    private fun tracker(name: String = ".gitignore"): IgnoreDotsTracker {
        val file = dir.resolve(name).apply { writeText(saved) }
        service?.lookup = { path, done ->
            lookups++
            paths.add(path)
            if (deferred) pending += done else done(answer)
        }
        return IgnoreDotsTracker(
            file,
            savedText = { saved },
            text = { text },
            serviceOf = { service },
            onChanged = { refreshes++ },
        ).also {
            it.connect()
            it.lookUp()
        }
    }

    private fun IgnoreDotsTracker.dotLines() = (0..6).filter { dotOf(it) != null }.toSet()

    @Test
    fun `answered effects give each pattern line its dot`() {
        val tracker = tracker()
        assertTrue(tracker.isKnown)
        assertEquals(setOf(1, 3, 4), tracker.dotLines())
    }

    @Test
    fun `a change of the service looks the effects up again`() {
        tracker()
        val before = lookups
        service?.changed()
        assertEquals(before + 1, lookups)
    }

    @Test
    fun `without a service nothing is known`() {
        service = null
        val tracker = tracker()
        assertFalse(tracker.isKnown)
        assertTrue(tracker.dotLines().isEmpty())
    }

    @Test
    fun `an older lookup's answer is dropped once a newer one has started`() {
        deferred = true
        val tracker = tracker()
        service?.changed()
        assertEquals(2, pending.size)
        pending[0](answer)
        assertFalse(tracker.isKnown)
        pending[1](answer)
        assertEquals(3, tracker.dotLines().size)
    }

    @Test
    fun `an answer after dispose is dropped, and changes make no lookup`() {
        deferred = true
        val tracker = tracker()
        tracker.dispose()
        pending.single()(answer)
        assertFalse(tracker.isKnown)
        val before = lookups
        service?.changed()
        assertEquals(before, lookups)
    }

    @Test
    fun `after the service changed the old one is no longer listened to`() {
        val tracker = tracker()
        val old = service
        service = IgnoreRuleEffects()
        tracker.connect()
        val before = lookups
        old?.changed()
        assertEquals(before, lookups)
    }

    @Test
    fun `lookUp asks the service again`() {
        val tracker = tracker()
        val before = lookups
        tracker.lookUp()
        assertEquals(before + 1, lookups)
    }

    @Test
    fun `a line inserted above moves the dots, an edited line loses its dot`() {
        val tracker = tracker()
        text = "new\n$saved"
        tracker.recompute()
        assertEquals(setOf(2, 4, 5), tracker.dotLines())
        text = saved.replace("build/", "builds/")
        tracker.recompute()
        assertEquals(setOf(3, 4), tracker.dotLines())
    }

    @Test
    fun `effects of a text that is not the one on disk are not kept`() {
        val tracker = tracker()
        dir.resolve(".gitignore").writeText("other\n")
        service?.changed()
        assertFalse(tracker.isKnown)
    }

    @Test
    fun `a Windows-1252 file on disk has the saved text`() {
        val tracker = tracker()
        // The saved text's comment is "# ä" now, in Windows-1252 on disk.
        saved = saved.replace("# c", "# ä")
        dir.resolve(".gitignore").writeBytes(saved.toByteArray(TextEncoding.WINDOWS_1252.charset))
        service?.changed()
        assertTrue(tracker.isKnown)
    }

    @Test
    fun `renamed away from gitignore there are no dots and no lookups`() {
        val tracker = tracker()
        tracker.moved(dir.resolve("notes.txt"))
        assertFalse(tracker.isKnown)
        val before = lookups
        service?.changed()
        assertEquals(before, lookups)
    }

    @Test
    fun `renamed away and back there are dots again`() {
        val tracker = tracker()
        dir.resolve(".dockerignore").writeText(saved)
        tracker.moved(dir.resolve(".dockerignore"))
        assertFalse(tracker.isKnown)
        tracker.moved(dir.resolve(".gitignore"))
        assertEquals(3, tracker.dotLines().size)
        val before = lookups
        service?.changed()
        assertEquals(before + 1, lookups)
    }

    @Test
    fun `moved to a gitignore in another folder looks up with the new path`() {
        val tracker = tracker()
        paths.clear()
        val target = dir.resolve("other").resolve(".gitignore")
        tracker.moved(target)
        assertEquals(listOf(target), paths)
    }

    @Test
    fun `a null answer after a known one removes the dots and refreshes the gutter`() {
        val tracker = tracker()
        assertTrue(tracker.isKnown)
        service?.lookup = { _, done -> done(null) }
        val before = refreshes
        tracker.lookUp()
        assertFalse(tracker.isKnown)
        assertNull(tracker.dotOf(1))
        assertEquals(before + 1, refreshes)
    }

    @Test
    fun `a file that is gone has no dots`() {
        val tracker = tracker()
        assertNotNull(tracker.dotOf(1))
        dir.resolve(".gitignore").deleteExisting()
        tracker.lookUp()
        assertFalse(tracker.isKnown)
        assertNull(tracker.dotOf(1))
    }
}
