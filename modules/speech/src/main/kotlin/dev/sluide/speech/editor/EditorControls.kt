package dev.sluide.speech.editor

import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.ui.ReadAloudModel
import dev.sluide.speech.ui.ReadAloudToolbar
import dev.sluide.speech.ui.ReadAloudUi
import dev.sluide.speech.ui.SetupNotice
import javafx.scene.Node

/** What an editor shows of a reading, apart from the highlight: the toolbar items and the setup notice. */
internal interface EditorControls {
    val toolbar: Node
    val notice: Node

    fun show(ui: ReadAloudUi)

    fun dispose()
}

/** [EditorControls] with the tool's [ReadAloudToolbar] (embedded in the editor's toolbar) and [SetupNotice]. */
internal class ReadAloudEditorControls(
    model: ReadAloudModel,
    play: () -> Unit,
    stylesheet: String,
) : EditorControls {
    private val bar = ReadAloudToolbar(model, play, embedded = true)
    private val setup = SetupNotice(model)
    private var shownNotice = emptyList<SetupProblem>()

    override val toolbar: Node = bar.node.apply { stylesheets += stylesheet }
    override val notice: Node = setup.node.apply { stylesheets += stylesheet }

    override fun show(ui: ReadAloudUi) {
        bar.show(ui)
        if (ui.notice != shownNotice) {
            shownNotice = ui.notice
            setup.show(ui.notice)
        }
    }

    override fun dispose() = setup.dispose()
}
