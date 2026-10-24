package dev.sluide.terminal.view

import dev.sluide.editorarea.EditorTab
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
        get() = closeWarning(name, command, view.session.isAlive) { view.session.runningPrograms() }

    override fun dispose() {
        view.dispose()
        onDisposed(this)
    }
}

/**
 * The warning for closing a terminal [name], `null` if nothing runs. [programs] is only asked for tabs without a
 * [command].
 */
internal fun closeWarning(
    name: String,
    command: String?,
    alive: Boolean,
    programs: () -> List<String>,
): String? {
    if (command != null) return if (alive) "$name is still running: $command" else null
    return programs()
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.let { "$name is still running ${it.joinToString(", ")}." }
}
