package dev.sluide.terminal

import com.jediterm.core.input.MouseEvent
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.mouse.MouseButtonCodes
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.TerminalSelection
import dev.sluide.terminal.view.Modifiers
import dev.sluide.terminal.view.TerminalMouse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Runs a real shell (`/bin/sh`, so user shell configuration does not interfere) in a pseudo terminal. */
class TerminalSessionTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var session: TerminalSession

    @AfterEach
    fun close() {
        if (::session.isInitialized) session.close()
    }

    private fun start(size: TermSize = TermSize(80, 24)) {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        session = TerminalSession(dir, NoDisplay, size, listOf("/bin/sh"), System.getenv())
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
            check(System.nanoTime() < deadline) {
                "No $what. Running programs: ${session.runningPrograms()}, shell children: ${shellChildren()}, " +
                    "screen:\n${screenLines().joinToString("\n")}"
            }
            Thread.sleep(20)
        }
    }

    /** All child processes of the shell with their state and command line, to explain failures. */
    private fun shellChildren(): List<String> =
        ProcessHandle
            .of(session.process.pid())
            .stream()
            .flatMap { it.children() }
            .map {
                "${it.pid()} alive=${it.isAlive} ${it.info().commandLine().orElse(
                    it.info().command().orElse("?"),
                )}"
            }.toList()

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
    fun `reports the programs the shell runs in the foreground and in the background`() {
        start()
        assertEquals(emptyList(), session.runningPrograms())

        run("sleep 300 & sleep 301")
        await("running programs") { session.runningPrograms() == listOf("sleep", "sleep") }

        // Ctrl+C ends the foreground program, the background job keeps running.
        session.send(byteArrayOf(3))
        await("one program left") { session.runningPrograms() == listOf("sleep") }
        // Not `kill $!`: macOS's /bin/sh (bash 3.2) treats `!` as history expansion in interactive shells.
        run("kill %1; wait")
        await("no programs") { session.runningPrograms().isEmpty() }
    }

    /** Starts `cat -v` after [modes] were switched on, so the bytes the terminal sends show up as text. */
    private fun startCatWith(modes: String) {
        start()
        run("printf '$modes'; echo ready; cat -v")
        awaitLine("ready")
    }

    @Test
    fun `a wheel turn is reported to programs that asked for mouse reports`() {
        // Alternate screen, mouse reports for clicks and the wheel, SGR format: what Claude Code's fullscreen TUI does.
        startCatWith("\\033[?1049h\\033[?1000h\\033[?1006h")

        assertTrue(session.sendMouse(4, 2, TerminalMouse.wheel(up = true, Modifiers(), 3)))
        assertTrue(session.sendMouse(4, 2, TerminalMouse.wheel(up = false, Modifiers(), 3)))
        session.send("\r".toByteArray())

        // Once per wheel event, at 1-based column 5, row 3; 64 is up, 65 down.
        awaitLine("^[[<64;5;3M^[[<65;5;3M")
    }

    @Test
    fun `a wheel turn in a full-screen program without mouse reports is sent as arrow keys`() {
        startCatWith("\\033[?1049h")

        assertTrue(session.sendMouse(0, 0, TerminalMouse.wheel(up = false, Modifiers(), 3)))
        session.send("\r".toByteArray())

        awaitLine("^[[B^[[B^[[B")
    }

    @Test
    fun `nothing is sent for the wheel on the normal screen without mouse reports`() {
        start()

        assertFalse(session.sendMouse(0, 0, TerminalMouse.wheel(up = true, Modifiers(), 3)))
    }

    @Test
    fun `clicks are reported to programs that asked for them`() {
        startCatWith("\\033[?1000h\\033[?1006h")

        val left = MouseButtonCodes.LEFT
        assertTrue(session.sendMouse(1, 0, TerminalMouse.event(MouseEvent.Type.PRESSED, left, Modifiers())))
        assertTrue(session.sendMouse(1, 0, TerminalMouse.event(MouseEvent.Type.RELEASED, left, Modifiers())))
        session.send("\r".toByteArray())

        awaitLine("^[[<0;2;1M^[[<0;2;1m")
    }

    @Test
    fun `closing ends the shell`() {
        start()
        assertTrue(session.isAlive)

        session.close()

        assertTrue(session.process.waitFor(5, TimeUnit.SECONDS))
        assertFalse(session.isAlive)
    }

    @Test
    fun `a shell that does not exist cannot be started`() {
        assertFailsWith<IOException> {
            TerminalSession(dir, NoDisplay, TermSize(80, 24), listOf(dir.resolve("no-shell").toString()), mapOf())
        }
    }

    @Test
    fun `the user's shell is SHELL, or bash if it is unset or blank`() {
        assertEquals(listOf("/bin/zsh", "-l"), TerminalSession.defaultShell(mapOf("SHELL" to "/bin/zsh")))
        assertEquals(listOf("/bin/bash", "-l"), TerminalSession.defaultShell(mapOf()))
        assertEquals(listOf("/bin/bash", "-l"), TerminalSession.defaultShell(mapOf("SHELL" to " ")))
        assertEquals(
            listOf("/bin/bash", "-l", "-c", "sbx env run"),
            TerminalSession.loginShellRunning("sbx env run", mapOf("SHELL" to "")),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-c", "sbx env run"),
            TerminalSession.loginShellRunning("sbx env run", mapOf("SHELL" to "/bin/zsh")),
        )
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
