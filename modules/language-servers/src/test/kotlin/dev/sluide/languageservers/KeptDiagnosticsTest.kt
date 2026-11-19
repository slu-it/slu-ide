package dev.sluide.languageservers

import javafx.beans.InvalidationListener
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** What a session keeps of the pushes for files no editor listens to: the latest list per file, and when it fires. */
class KeptDiagnosticsTest {
    private val kept = KeptDiagnostics()
    private val fired = AtomicInteger()
    private val file = Path.of("/work/tsconfig.json")
    private val uri = LspUris.of(file)

    init {
        kept.changes.addListener(InvalidationListener { fired.incrementAndGet() })
    }

    private fun diagnostic(message: String) = Diagnostic(Range(Position(0, 0), Position(0, 1)), message)

    @Test
    fun `a push is kept per file, a push with the same list changes nothing, another list replaces it`() {
        kept.keep(uri, listOf(diagnostic("one")))
        kept.keep(uri, listOf(diagnostic("one")))

        assertEquals(listOf(diagnostic("one")), kept.of(file))
        assertEquals(1, fired.get(), "the same list again is no change")

        kept.keep(uri, listOf(diagnostic("two")))

        assertEquals(listOf(diagnostic("two")), kept.of(file))
        assertEquals(2, fired.get())
        assertEquals(emptyList(), kept.of(Path.of("/work/other.json")))
    }

    @Test
    fun `an empty push removes the file and fires once, an empty push for a file nothing was kept for does not fire`() {
        kept.keep(uri, emptyList())

        assertEquals(0, fired.get(), "nothing was kept, nothing changed")

        kept.keep(uri, listOf(diagnostic("one")))
        kept.keep(uri, emptyList())

        assertEquals(emptyList(), kept.of(file))
        assertEquals(2, fired.get())
        // An empty push removes the entry: clearing now has nothing to forget and so fires nothing.
        kept.clear()
        assertEquals(2, fired.get())
    }

    @Test
    fun `clearing forgets everything and fires if something was kept and it is asked to, not otherwise`() {
        kept.clear()

        assertEquals(0, fired.get(), "nothing kept")

        kept.keep(uri, listOf(diagnostic("one")))
        kept.clear(fire = false)

        assertEquals(emptyList(), kept.of(file))
        assertEquals(1, fired.get(), "clearing without firing, as a closing session does")

        kept.keep(uri, listOf(diagnostic("one")))
        kept.clear()

        assertEquals(emptyList(), kept.of(file))
        assertEquals(3, fired.get())
    }
}
