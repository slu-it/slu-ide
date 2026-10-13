package dev.sluide.terminal

import dev.sluide.editor.EditorTab
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Starts terminals as editor tabs and ends their shells when the application stops. Terminals use the terminal font
 * of the [fontSettings] and follow its changes.
 */
@Component
class Terminals(
    private val fontSettings: FontSettings,
) : AutoCloseable {
    private val open = mutableListOf<TerminalTab>()
    private var started = 0

    /**
     * Starts the user's shell in [folder] and returns the tab showing it, named "Terminal 1", "Terminal 2", …
     * Closing the tab ends the shell. Must be called on the FX thread.
     */
    fun open(folder: Path): EditorTab {
        val terminalFont = fontSettings.font(FontArea.TERMINAL)
        val view = TerminalView({ display, size -> TerminalSession(folder, display, size) }, font = terminalFont.get())
        val fontSubscription = terminalFont.subscribe { spec -> view.font = spec }
        return TerminalTab("Terminal ${++started}", folder, view) { tab ->
            fontSubscription.unsubscribe()
            open.remove(tab)
        }.also(open::add)
    }

    /** The number of terminals whose tab is not closed yet. */
    val openCount: Int get() = open.size

    override fun close() = open.toList().forEach(TerminalTab::dispose)
}
