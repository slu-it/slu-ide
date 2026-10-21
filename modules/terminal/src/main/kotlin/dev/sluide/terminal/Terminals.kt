package dev.sluide.terminal

import dev.sluide.editorarea.EditorTab
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.terminal.settings.TerminalScrollSettings
import dev.sluide.terminal.view.ScrollSpeeds
import dev.sluide.terminal.view.TerminalTab
import dev.sluide.terminal.view.TerminalView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path

/** Thrown when a terminal cannot be started, e.g. because its shell does not exist; the message says why. */
class TerminalStartException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * The command line of the user's login shell (`SHELL` in [environment]) running [command] and then ending, with the
 * login environment (e.g. its `PATH`): the shell that [Terminals.open] runs a command in.
 */
fun loginShellRunning(
    command: String,
    environment: Map<String, String>,
): List<String> = TerminalSession.loginShellRunning(command, environment)

/**
 * Starts terminals as editor tabs and ends their shells when it is closed, together with its window. Terminals use
 * the terminal font of the [fontSettings] and the speeds of the [scrollSettings], and follow their changes.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Terminals(
    private val fontSettings: FontSettings,
    private val scrollSettings: TerminalScrollSettings,
    /** The environment of the started shells; its `SHELL` is the user's shell. */
    private val environment: Map<String, String>,
) : AutoCloseable {
    @Autowired
    constructor(fontSettings: FontSettings, scrollSettings: TerminalScrollSettings) : this(
        fontSettings,
        scrollSettings,
        System.getenv(),
    )

    private val open = mutableListOf<TerminalTab>()
    private var started = 0

    /**
     * Starts the user's shell in [folder] and returns the tab showing it, named "Terminal 1", "Terminal 2", …
     * Closing the tab ends the shell. Must be called on the FX thread.
     *
     * @throws TerminalStartException if the shell cannot be started; the next terminal gets the same number.
     */
    fun open(folder: Path): EditorTab =
        open(folder, "Terminal ${started + 1}", TerminalSession.defaultShell(environment), environment)
            .also { started++ }

    /**
     * Runs [command] in the user's login shell (`SHELL` in [environment]) in [folder] and returns the tab showing it,
     * named [name]. The tab shows "(exited)" when the command ends; closing the tab ends it. Must be called on the FX
     * thread.
     *
     * @throws TerminalStartException if the shell cannot be started.
     */
    fun open(
        folder: Path,
        name: String,
        command: String,
        environment: Map<String, String> = this.environment,
    ): EditorTab = open(folder, name, TerminalSession.loginShellRunning(command, environment), environment, command)

    private fun open(
        folder: Path,
        name: String,
        command: List<String>,
        environment: Map<String, String>,
        shownCommand: String? = null,
    ): EditorTab {
        val terminalFont = fontSettings.font(FontArea.TERMINAL)
        val view =
            try {
                TerminalView(
                    { display, size -> TerminalSession(folder, display, size, command, environment) },
                    font = terminalFont.get(),
                    scrollSpeeds = scrollSpeeds(),
                )
            } catch (e: IOException) {
                // pty4j's messages alone are cryptic ("Exec_tty error:Unknown reason"), so name shell and folder.
                throw TerminalStartException("${command.first()} could not be started in $folder (${e.message}).", e)
            }
        val fontSubscription = terminalFont.subscribe { spec -> view.font = spec }
        val scrollSubscription =
            scrollSettings.trackpad
                .subscribe { _ -> view.scrollSpeeds = scrollSpeeds() }
                .and(scrollSettings.wheel.subscribe { _ -> view.scrollSpeeds = scrollSpeeds() })
        return TerminalTab(name, folder, view, shownCommand) { tab ->
            fontSubscription.unsubscribe()
            scrollSubscription.unsubscribe()
            open.remove(tab)
        }.also(open::add)
    }

    private fun scrollSpeeds() = ScrollSpeeds(scrollSettings.trackpad.get(), scrollSettings.wheel.get())

    /** The number of terminals whose tab is not closed yet. */
    val openCount: Int get() = open.size

    override fun close() = open.toList().forEach(TerminalTab::dispose)
}
