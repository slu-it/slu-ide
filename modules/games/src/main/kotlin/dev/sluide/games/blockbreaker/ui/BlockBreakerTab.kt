package dev.sluide.games.blockbreaker.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Block Breaker shown as an editor tab. */
internal class BlockBreakerTab(
    private val view: BlockBreakerView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Block Breaker"
        }
    override val tooltip: String = "Clear the wall with paddle and ball"
    override val focusOnShow: Boolean = true
    override val content: Node get() = view.node

    override fun dispose() = view.dispose()
}
