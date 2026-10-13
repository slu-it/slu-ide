package dev.sluide.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.TerminalSelection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Runs a real shell (`/bin/sh`, so user shell configuration does not interfere) in a pseudo terminal. */
class TerminalSessionTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var session: TerminalSession

    @AfterEach
    fun close() = session.close()

    private fun start(size: TermSize = TermSize(80, 24)) {
        session = TerminalSession(dir, NoDisplay, size, command = listOf("/bin/sh"))
        // Input sent before the prompt is echoed first and its output then shares a line with the prompt.
        await("a prompt") { screenLines().any { it.isNotBlank() } }
    }

    private fun run(command: String) = session.send("$command\r".toByteArray())

    private fun screenLines(): List<String> {
        session.buffer.lock()
        try {
            return session.buffer
                .getScreenLines()
                .lines()
                .map { it.trimEnd() }
        } finally {
            session.buffer.unlock()
        }
    }

    private fun awaitLine(line: String) = await("line '$line'") { line in screenLines() }

    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "No $what on screen:\n${screenLines().joinToString("\n")}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `the shell starts in the given folder`() {
        start()

        run("pwd")

        awaitLine(dir.toString())
    }

    @Test
    fun `output is interpreted by the emulator`() {
        start()

        run("printf 'a\\033[31mb\\033[0m-%s\\n' \$((40 + 2))")

        awaitLine("ab-42")
        val row = screenLines().indexOf("ab-42")
        assertEquals(null, session.buffer.getStyleAt(0, row)?.foreground)
        assertEquals(TerminalColor.index(1), session.buffer.getStyleAt(1, row)?.foreground, "b is red")
    }

    @Test
    fun `the shell sees terminal type and size, also after a resize`() {
        start(TermSize(100, 30))
        run("echo \"\$TERM\" \$(stty size)")
        awaitLine("xterm-256color 30 100")

        session.resize(TermSize(90, 20))
        // Resizing is asynchronous: wait until the shell sees the new size.
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while ("size 20 90" !in screenLines()) {
            check(System.nanoTime() < deadline) { screenLines().joinToString("\n") }
            run("echo size \$(stty size)")
            Thread.sleep(200)
        }
        assertEquals(20, session.buffer.height)
    }

    @Test
    fun `closing ends the shell`() {
        start()
        assertTrue(session.isAlive)

        session.close()

        assertTrue(session.process.waitFor(5, TimeUnit.SECONDS))
        assertFalse(session.isAlive)
    }

    private object NoDisplay : TerminalDisplay {
        override fun setCursor(
            x: Int,
            y: Int,
        ) {}

        override fun setCursorShape(cursorShape: CursorShape?) {}

        override fun beep() {}

        override fun scrollArea(
            scrollRegionTop: Int,
            scrollRegionSize: Int,
            dy: Int,
        ) {}

        override fun setCursorVisible(isCursorVisible: Boolean) {}

        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {}

        override fun getWindowTitle() = ""

        override fun setWindowTitle(windowTitle: String) {}

        override fun getSelection(): TerminalSelection? = null

        override fun terminalMouseModeSet(mouseMode: MouseMode) {}

        override fun setMouseFormat(mouseFormat: MouseFormat) {}

        override fun ambiguousCharsAreDoubleWidth() = false
    }
}
