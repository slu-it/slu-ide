package dev.sluide.terminal.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasteAndCloseWarningTest {
    @Test
    fun `a paste sends every line break as Enter`() {
        assertEquals("a\rb\rc\rd", pastePayload("a\nb\r\nc\rd", bracketed = false))
        assertEquals("no break", pastePayload("no break", bracketed = false))
    }

    @Test
    fun `a bracketed paste is wrapped in the markers`() {
        assertEquals("\u001b[200~a\rb\u001b[201~", pastePayload("a\r\nb", bracketed = true))
    }

    @Test
    fun `a terminal without a command warns about the distinct programs it runs`() {
        assertEquals(
            "Terminal 1 is still running java, sleep.",
            closeWarning("Terminal 1", null, alive = true) { listOf("java", "sleep", "java") },
        )
        assertNull(closeWarning("Terminal 1", null, alive = true) { emptyList() })
    }

    @Test
    fun `a terminal with a command warns while it runs only`() {
        assertEquals(
            "Build is still running: make",
            closeWarning("Build", "make", alive = true) { error("not asked") },
        )
        assertNull(closeWarning("Build", "make", alive = false) { error("not asked") })
    }

    @Test
    fun `a title names the exit code of an ended terminal unless it is 0 or unknown`() {
        assertEquals("Terminal 1", terminalTitle("Terminal 1", exited = false, exitCode = null))
        assertEquals("Terminal 1 (exited)", terminalTitle("Terminal 1", exited = true, exitCode = 0))
        assertEquals("Terminal 1 (exited 3)", terminalTitle("Terminal 1", exited = true, exitCode = 3))
        assertEquals("Terminal 1 (exited)", terminalTitle("Terminal 1", exited = true, exitCode = null))
    }
}
