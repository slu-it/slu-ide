package dev.sluide.terminal

import dev.sluide.editor.EditorTab
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import java.nio.file.Path

/** A terminal shown as an editor tab; closing the tab ends its shell and then calls [onDisposed]. */
internal class TerminalTab(
    private val name: String,
    folder: Path,
    val view: TerminalView,
    private val command: String? = null,
    private val onDisposed: (TerminalTab) -> Unit = {},
) : EditorTab {
    override val title: ObservableValue<String> =
        Bindings.createStringBinding({ if (view.exited.get()) "$name (exited)" else name }, view.exited)

    override val tooltip: String = "Terminal in $folder" + (command?.let { ": $it" } ?: "")

    override val content: Node get() = view

    /**
     * Asks before closing while the shell runs programs, since closing hangs up on them. A tab running a [command]
     * asks as long as the command runs: the login shell runs a single command in its own process, so the command
     * has no shell above it whose children could be checked.
     */
    override val closeWarning: String?
        get() {
            if (command != null) return if (view.session.isAlive) "$name is still running: $command" else null
            return view.session
                .runningPrograms()
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { "$name is still running ${it.joinToString(", ")}." }
        }

    override fun dispose() {
        view.dispose()
        onDisposed(this)
    }
}
