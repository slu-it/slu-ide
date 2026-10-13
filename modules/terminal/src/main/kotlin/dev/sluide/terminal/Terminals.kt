package dev.sluide.terminal

import dev.sluide.editor.EditorTab
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Starts terminals as editor tabs and ends their shells when the application stops. */
@Component
class Terminals : AutoCloseable {
    private val open = mutableListOf<TerminalTab>()
    private var started = 0

    /**
     * Starts the user's shell in [folder] and returns the tab showing it, named "Terminal 1", "Terminal 2", …
     * Closing the tab ends the shell. Must be called on the FX thread.
     */
    fun open(folder: Path): EditorTab {
        val view = TerminalView({ display, size -> TerminalSession(folder, display, size) })
        return TerminalTab("Terminal ${++started}", folder, view, onDisposed = open::remove).also(open::add)
    }

    /** The number of terminals whose tab is not closed yet. */
    val openCount: Int get() = open.size

    override fun close() = open.toList().forEach(TerminalTab::dispose)
}
