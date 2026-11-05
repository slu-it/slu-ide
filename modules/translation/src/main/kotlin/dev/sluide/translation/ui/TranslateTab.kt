package dev.sluide.translation.ui

import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** One Translate tab titled [title]. Closing it ends its translation and drops its text ([onClose] ends the model). */
internal class TranslateTab(
    title: String,
    private val view: TranslateView,
    private val onClose: () -> Unit,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = title
        }
    override val tooltip: String = "Translate text"
    override val content: Node get() = view.node
    override val activity: ObservableValue<ViewActivity> get() = view.activity

    override fun dispose() {
        view.dispose()
        onClose()
    }
}
