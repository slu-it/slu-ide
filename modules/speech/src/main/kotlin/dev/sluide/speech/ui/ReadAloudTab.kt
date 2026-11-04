package dev.sluide.speech.ui

import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** The Read Aloud tool shown as an editor tab. Closing it stops the reading; the text, voice and speed stay. */
internal class ReadAloudTab(
    private val model: ReadAloudModel,
    private val view: ReadAloudView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Read Aloud"
        }
    override val tooltip: String = "Read text aloud"
    override val content: Node get() = view.node
    override val activity: ObservableValue<ViewActivity> get() = view.activity

    override fun dispose() {
        view.dispose()
        model.stop()
    }
}
