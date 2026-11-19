package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.LogRecords
import javafx.beans.InvalidationListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.assertEquals

/** The observable that tells the workspace's listeners; no scene needed. */
class SignalTest {
    private val signal = Signal()
    private val logs = LogRecords(Logger.getLogger(Signal::class.java.name))

    @AfterEach
    fun closeLogs() = logs.close()

    @Test
    fun `every listener hears each fire, a removed one no more`() {
        val heard = mutableListOf<String>()
        val a = InvalidationListener { heard += "a" }
        val b = InvalidationListener { heard += "b" }
        signal.addListener(a)
        signal.addListener(b)

        signal.fire()
        signal.removeListener(a)
        signal.fire()

        assertEquals(listOf("a", "b", "b"), heard)
    }

    @Test
    fun `a listener that throws is logged and the others still run`() {
        val heard = mutableListOf<String>()
        signal.addListener { throw IllegalStateException("broken") }
        signal.addListener { heard += "after" }

        signal.fire()

        assertEquals(listOf("after"), heard)
        assertEquals(listOf("lsp: a workspace listener failed"), logs.messages(Level.SEVERE))
    }
}
