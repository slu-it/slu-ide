package dev.sluide.speech.ui

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.engine.SetupProblem
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox

/**
 * The Read Aloud tab's content: the setup notice and the toolbar above a text editor in the editor font ([editorCss],
 * a CSS style). It only shows [model] and forwards the user's input; it owns [ReadAloudModel.onChange] until
 * [dispose].
 *
 * Keys on the editor: while the text is locked, Space pauses or resumes and Escape stops; while it is editable,
 * Shortcut+Enter plays from the caret, or only the selection if there is one. A double-click on the locked text
 * jumps to the sentence under it.
 */
internal class ReadAloudView(
    private val model: ReadAloudModel,
    private val editorCss: ObservableValue<String>,
) {
    private val editor = CodeEditors.text(model.text, highlighter = null)
    private val surface = TextEditorSurface(editor)
    private val playSelectionOrCaret = { model.play(surface.caretOffset, surface.selection) }
    private val binding = ReadingBinding(model, surface, playSelectionOrCaret)
    private val toolbar = ReadAloudToolbar(model, playSelectionOrCaret)
    private val notice = SetupNotice(model)

    /** What the reading is doing, for the tab header's marker. */
    val activity = SimpleObjectProperty(ViewActivity.NONE)

    val node: Node =
        BorderPane().apply {
            styleClass += "speech-view"
            stylesheets +=
                checkNotNull(ReadAloudView::class.java.getResource("/dev/sluide/speech/speech.css")).toExternalForm()
            top = VBox(notice.node, toolbar.node)
            center = editor.node
        }

    private var appliedVersion = model.ui.textVersion
    private var appliedNotice = emptyList<SetupProblem>()
    private val installed: () -> Unit = ::refresh

    init {
        editor.node.styleProperty().bind(editorCss)
        editor.textProperty.subscribe { text -> model.textEdited(text) }
        editor.node.addEventFilter(KeyEvent.KEY_PRESSED) { event -> if (binding.onKey(event)) event.consume() }
        editor.node.addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.clickCount == 2) binding.onDoubleClick()
        }
        model.onChange = installed
        refresh()
    }

    private fun refresh() {
        val ui = model.ui
        if (ui.textVersion != appliedVersion) {
            appliedVersion = ui.textVersion
            editor.replaceText(model.text)
            binding.resetApplied()
        }
        binding.apply(ui)
        toolbar.show(ui)
        if (ui.notice != appliedNotice) {
            appliedNotice = ui.notice
            notice.show(ui.notice)
        }
        activity.set(ui.activity)
    }

    /** The model stops notifying this view, unless a newer view took it over; the editor leaves the font. */
    fun dispose() {
        if (model.onChange === installed) model.onChange = {}
        notice.dispose()
        editor.node.styleProperty().unbind()
    }
}
