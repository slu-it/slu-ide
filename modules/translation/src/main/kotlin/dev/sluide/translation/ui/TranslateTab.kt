package dev.sluide.translation.ui

import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Translate tool shown as an editor tab. Closing it keeps the text, languages and translation (the model's). */
internal class TranslateTab(
    private val view: TranslateView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Translate"
        }
    override val tooltip: String = "Translate text"
    override val content: Node get() = view.node
    override val activity: ObservableValue<ViewActivity> get() = view.activity

    override fun dispose() = view.dispose()
}
