package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.binding.SourcePosition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Back and Forward: the places before jumps, the way forward, the cap and places in files that are gone. */
class NavigationHistoryTest {
    private val missing = mutableSetOf<Path>()
    private val history = NavigationHistory(cap = 3) { it !in missing }

    private fun at(
        name: String,
        line: Int = 0,
    ) = SourcePosition(Path.of(name), line, 0)

    @Test
    fun `back returns to the place before the jump and forward goes to where the user was then`() {
        assertFalse(history.canGoBack.get())
        history.record(at("a.py", 1), at("b.py", 5))
        assertTrue(history.canGoBack.get())
        assertFalse(history.canGoForward.get())

        // The user moved on in b.py since the jump: Forward comes back there.
        assertEquals(at("a.py", 1), history.back(at("b.py", 7)))
        assertFalse(history.canGoBack.get())
        assertTrue(history.canGoForward.get())
        assertEquals(at("b.py", 7), history.forward(at("a.py", 1)))
        assertEquals(at("a.py", 1), history.back(null), "unknown current place: where the step arrived")
        assertEquals(at("b.py", 7), history.forward(null))
        assertNull(history.forward(null))
    }

    @Test
    fun `a new jump forgets the way forward, and nothing is recorded for a jump to the same place`() {
        history.record(at("a.py"), at("b.py"))
        history.back(at("b.py"))
        history.record(at("a.py"), at("c.py"))
        assertFalse(history.canGoForward.get())
        history.record(at("c.py"), at("c.py"))
        assertEquals(listOf(at("a.py")) to emptyList(), history.places)
    }

    @Test
    fun `at most cap places are kept, the oldest dropped first`() {
        for (line in 0..4) history.record(at("a.py", line), at("a.py", line + 10))
        assertEquals(listOf(at("a.py", 2), at("a.py", 3), at("a.py", 4)), history.places.first)
    }

    @Test
    fun `places in files that no longer exist, and the current place itself, are skipped`() {
        history.record(at("a.py"), at("b.py"))
        history.record(at("b.py"), at("c.py"))
        history.record(at("c.py", 3), at("d.py"))
        missing.add(Path.of("b.py"))

        assertEquals(at("a.py"), history.back(at("c.py", 3)))
        assertNull(history.back(at("a.py")))
        assertEquals(listOf(at("c.py", 3)), history.places.second, "only where the user was is the way forward")
    }
}
