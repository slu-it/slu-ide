package dev.sluide.speech.ui

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.engine.SetupProblem
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.input.KeyCode
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
 * Shortcut+Enter plays from the caret. A double-click on the locked text jumps to the sentence under it.
 */
internal class ReadAloudView(
    private val model: ReadAloudModel,
    private val editorCss: ObservableValue<String>,
) {
    private val editor = CodeEditors.text(model.text, highlighter = null)
    private val toolbar = ReadAloudToolbar(model) { editor.caretOffset }
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
    private var appliedOverlay = emptyList<StyledRange>()
    private var appliedReveal: IntRange? = null
    private var appliedNotice = emptyList<SetupProblem>()
    private val installed: () -> Unit = ::refresh

    init {
        editor.node.styleProperty().bind(editorCss)
        editor.textProperty.subscribe { text -> model.textEdited(text) }
        editor.node.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        editor.node.addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.clickCount == 2 && !model.ui.editable) model.jumpTo(editor.caretOffset)
        }
        model.onChange = installed
        refresh()
    }

    private fun onKey(event: KeyEvent) {
        val handled =
            if (model.ui.editable) {
                (event.code == KeyCode.ENTER && event.isShortcutDown).also { if (it) model.play(editor.caretOffset) }
            } else if (event.isShortcutDown || event.isAltDown || event.isShiftDown) {
                false
            } else if (event.code == KeyCode.SPACE) {
                model.togglePause()
                true
            } else if (event.code == KeyCode.ESCAPE) {
                model.stop()
                true
            } else {
                false
            }
        if (handled) event.consume()
    }

    private fun refresh() {
        val ui = model.ui
        if (ui.textVersion != appliedVersion) {
            appliedVersion = ui.textVersion
            editor.replaceText(model.text)
            appliedOverlay = emptyList()
            appliedReveal = null
        }
        editor.editable = ui.editable
        if (ui.overlay != appliedOverlay) {
            appliedOverlay = ui.overlay
            editor.overlay(ui.overlay)
        }
        if (ui.reveal != appliedReveal) {
            appliedReveal = ui.reveal
            ui.reveal?.let { editor.reveal(it.first, it.last + 1) }
        }
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
