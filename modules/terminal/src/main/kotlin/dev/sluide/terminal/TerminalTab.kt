package dev.sluide.terminal

import dev.sluide.editor.EditorTab
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import java.nio.file.Path

/** A terminal shown as an editor tab; closing the tab ends its shell and then calls [onDisposed]. */
internal class TerminalTab(
    name: String,
    folder: Path,
    val view: TerminalView,
    private val onDisposed: (TerminalTab) -> Unit = {},
) : EditorTab {
    override val title: ObservableValue<String> =
        Bindings.createStringBinding({ if (view.exited.get()) "$name (exited)" else name }, view.exited)

    override val tooltip: String = "Terminal in $folder"

    override val content: Node get() = view

    override fun dispose() {
        view.dispose()
        onDisposed(this)
    }
}
