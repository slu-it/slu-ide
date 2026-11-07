package dev.sluide.games.orbitguard.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Orbit Guard shown as an editor tab. */
internal class OrbitGuardTab(
    private val view: OrbitGuardView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Orbit Guard"
        }
    override val tooltip: String = "Defend the orbit against the descending fleet"
    override val focusOnShow: Boolean = true
    override val content: Node get() = view.node

    override fun dispose() = view.dispose()
}
