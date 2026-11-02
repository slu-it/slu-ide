package dev.sluide.databases.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/**
 * The Databases tab of a window (ADR 0121). Closing it asks nothing and keeps the connections, which live in the
 * window's `Databases`; [onDispose] runs once it is closed, after the view is disposed.
 */
internal class DatabasesTab(
    val view: DatabasesTabView,
    private val onDispose: () -> Unit,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Databases"
        }
    override val tooltip: String = "Database connections, consoles and object trees"
    override val content: Node get() = view.node

    override fun dispose() {
        view.dispose()
        onDispose()
    }
}
