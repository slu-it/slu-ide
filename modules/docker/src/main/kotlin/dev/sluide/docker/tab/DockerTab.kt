package dev.sluide.docker.tab

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Docker tab of a window (ADR 0118); [onDispose] runs once it is closed, after the view is disposed. */
internal class DockerTab(
    val view: DockerTabView,
    private val onDispose: () -> Unit,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Docker"
        }
    override val tooltip: String = "Containers, images, volumes and networks"
    override val content: Node get() = view.node

    override fun dispose() {
        view.dispose()
        onDispose()
    }
}
