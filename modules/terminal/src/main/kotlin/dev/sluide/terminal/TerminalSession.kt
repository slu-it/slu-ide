package dev.sluide.terminal

import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
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

/**
 * A shell running in a pseudo terminal, started in [folder], whose output is interpreted by the JediTerm emulator into
 * [buffer]. The emulator reads on a background thread and reports changes to [display] from there.
 */
internal class TerminalSession(
    folder: Path,
    display: TerminalDisplay,
    size: TermSize,
    command: List<String> = defaultShell(),
    environment: Map<String, String> = System.getenv(),
) : AutoCloseable {
    // The terminal sets the current style, the buffer writes text with it: they must share the state.
    private val styleState = StyleState()
    val buffer = TerminalTextBuffer(size.columns, size.rows, styleState)
    val terminal = JediTerminal(display, buffer, styleState)
    val process: PtyProcess =
        PtyProcessBuilder(command.toTypedArray())
            .setDirectory(folder.toString())
            .setEnvironment(environment + mapOf("TERM" to "xterm-256color", "PWD" to folder.toString()))
            .setInitialColumns(size.columns)
            .setInitialRows(size.rows)
            .setRedirectErrorStream(true)
            .start()

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
        executors.unboundedExecutorService.execute(starter::start)
    }

    val isAlive: Boolean get() = process.isAlive

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

    /** Resizes the screen buffer and tells the shell about the new size. */
    fun resize(size: TermSize) = starter.postResize(size, RequestOrigin.User)

    /** Ends the shell and the emulator thread. */
    override fun close() {
        starter.requestEmulatorStop()
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
        /** The user's login shell, falling back to `/bin/bash`, started as a login shell like a terminal app does. */
        fun defaultShell(): List<String> =
            listOf(System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/bash", "-l")
    }
}
