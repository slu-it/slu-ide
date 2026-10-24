package dev.sluide.terminal

import com.jediterm.core.input.MouseEvent
import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.mouse.MouseEventProcessingSettings
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

private val log = System.getLogger(TerminalSession::class.java.name)

private const val EXIT_GRACE_SECONDS = 2L

/** The terminal type shells are told (`TERM`), in terminals and in the program check ([loginShellFinds]). */
internal const val TERMINAL_TYPE = "xterm-256color"

/**
 * A shell running in a pseudo terminal, started in [folder], whose output is interpreted by the JediTerm emulator into
 * [buffer]. The emulator reads on a background thread and reports changes to [display] from there. Throws
 * [java.io.IOException] if the shell cannot be started (e.g. [command] does not exist).
 */
internal class TerminalSession(
    folder: Path,
    display: TerminalDisplay,
    size: TermSize,
    command: List<String>,
    environment: Map<String, String>,
) : AutoCloseable {
    // Started first: if it fails (IOException), the display has not been called yet, so no repaint of a view without
    // a session is scheduled.
    val process: PtyProcess =
        PtyProcessBuilder(command.toTypedArray())
            .setDirectory(folder.toString())
            .setEnvironment(environment + mapOf("TERM" to TERMINAL_TYPE, "PWD" to folder.toString()))
            .setInitialColumns(size.columns)
            .setInitialRows(size.rows)
            .setRedirectErrorStream(true)
            .start()

    // The terminal sets the current style, the buffer writes text with it: they must share the state.
    private val styleState = StyleState()
    val buffer = TerminalTextBuffer(size.columns, size.rows, styleState)
    val terminal = JediTerminal(display, buffer, styleState)

    private val executors = ExecutorServices()
    private val connector = PtyConnector(process, command)
    private val starter =
        TerminalStarter(
            terminal,
            connector,
            TtyBasedArrayDataStream(connector),
            TerminalTypeAheadManager(NoTypeAhead),
            executors,
        )

    init {
        // The program's name and pid only: never its arguments or environment (ADR 0060).
        val program = command.firstOrNull()?.let { Path.of(it).fileName?.toString() ?: it }.orEmpty()
        val started = System.nanoTime()
        log.log(System.Logger.Level.INFO, "Terminal process $program started (pid ${process.pid()}) in $folder")
        executors.unboundedExecutorService.execute {
            try {
                starter.start()
            } finally {
                // The emulator's read ended: the shell exited, or the session was closed.
                logExit(program, started)
            }
        }
    }

    private fun logExit(
        program: String,
        started: Long,
    ) {
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        val exited =
            try {
                process.waitFor(EXIT_GRACE_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // The session was closed (the pool is shut down), which ends the shell.
                Thread.currentThread().interrupt()
                log.log(
                    System.Logger.Level.INFO,
                    "Terminal process $program (pid ${process.pid()}) closed after $seconds s",
                )
                return
            }
        val outcome = if (exited) "exited with code ${process.exitValue()}" else "ended its output but still runs"
        log.log(System.Logger.Level.INFO, "Terminal process $program (pid ${process.pid()}) $outcome after $seconds s")
    }

    val isAlive: Boolean get() = process.isAlive

    /** The process's exit code once it has ended (after [PtyProcess.onExit] completed), `null` while it runs. */
    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    /**
     * The names of the programs the shell started that are still running (e.g. `java`), in the foreground or as
     * background jobs; empty when only the shell itself runs.
     */
    fun runningPrograms(): List<String> =
        ProcessHandle
            .of(process.pid())
            .map { shell -> shell.children().filter(ProcessHandle::isAlive).toList() }
            .orElse(emptyList())
            .map { child ->
                child
                    .info()
                    .command()
                    .map { Path.of(it).fileName.toString() }
                    .orElse("a process")
            }

    /** Sends input typed or pasted by the user to the shell. */
    fun send(bytes: ByteArray) = starter.sendBytes(bytes, true)

    /**
     * Sends a mouse event at a screen cell (0-based) to the running program, encoded as the program asked for. Without
     * mouse reports, a wheel turn in a full-screen program is sent as arrow keys. Returns whether anything was sent.
     */
    fun sendMouse(
        column: Int,
        row: Int,
        event: MouseEvent,
    ): Boolean =
        terminal.onMouseEvent(
            column,
            row,
            event,
            MouseEventProcessingSettings(true, buffer.isUsingAlternateBuffer, true),
        )

    /** Resizes the screen buffer and tells the shell about the new size. */
    fun resize(size: TermSize) = starter.postResize(size, RequestOrigin.User)

    /** Ends the shell and the emulator thread. */
    override fun close() {
        starter.requestEmulatorStop()
        // destroy() alone is not enough: it sends SIGTERM, which interactive shells such as bash ignore. Closing the
        // connector right after it closes the pseudo terminal, and that hangup (SIGHUP) is what ends the shell and
        // its jobs. Keep both, and do not wait for the process in between.
        process.destroy()
        connector.close()
        executors.shutdownNow()
    }

    private class PtyConnector(
        private val process: PtyProcess,
        command: List<String>,
    ) : ProcessTtyConnector(process, Charsets.UTF_8, command) {
        override fun getName() = "Local"

        override fun resize(termSize: TermSize) {
            if (process.isAlive) process.winSize = WinSize(termSize.columns, termSize.rows)
        }
    }

    private class ExecutorServices : TerminalExecutorServiceManager {
        private val threads = ThreadFactory { Thread(it, "terminal").apply { isDaemon = true } }
        private val single = Executors.newSingleThreadScheduledExecutor(threads)
        private val unbounded = Executors.newCachedThreadPool(threads)

        override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = single

        override fun getUnboundedExecutorService(): ExecutorService = unbounded

        override fun shutdownWhenAllExecuted() {
            single.shutdown()
            unbounded.shutdown()
        }

        fun shutdownNow() {
            // Lets queued writes (e.g. closing the connector) run, but accepts nothing new.
            single.shutdown()
            unbounded.shutdownNow()
        }
    }

    /** Type-ahead prediction is an IntelliJ feature for slow remote shells; it stays off here. */
    private object NoTypeAhead : TypeAheadTerminalModel {
        override fun insertCharacter(
            ch: Char,
            index: Int,
        ) {}

        override fun removeCharacters(
            from: Int,
            count: Int,
        ) {}

        override fun moveCursor(index: Int) {}

        override fun forceRedraw() {}

        override fun clearPredictions() {}

        override fun lock() {}

        override fun unlock() {}

        override fun isUsingAlternateBuffer() = false

        override fun getCurrentLineWithCursor() = TypeAheadTerminalModel.LineWithCursorX(StringBuffer(), 0)

        override fun getTerminalWidth() = 0

        override fun isTypeAheadEnabled() = false

        override fun getLatencyThreshold() = 0L

        override fun getShellType() = TypeAheadTerminalModel.ShellType.Unknown
    }

    companion object {
        /**
         * The user's shell (`SHELL` in [environment], falling back to `/bin/bash`), started as a login shell like a
         * terminal app does.
         */
        fun defaultShell(environment: Map<String, String>): List<String> = listOf(userShell(environment), "-l")

        /**
         * The login shell (`SHELL` in [environment]) running [command] and then ending, interactive (`-i`) so that it
         * reads the same startup files as a terminal tab (e.g. `.zshrc`, where SDKMAN or nvm set `PATH` and
         * `JAVA_HOME`), not only the login profile (ADR 0087).
         */
        fun loginShellRunning(
            command: String,
            environment: Map<String, String>,
        ): List<String> = listOf(userShell(environment), "-l", "-i", "-c", command)

        private fun userShell(environment: Map<String, String>) =
            environment["SHELL"]?.takeIf { it.isNotBlank() } ?: "/bin/bash"
    }
}
