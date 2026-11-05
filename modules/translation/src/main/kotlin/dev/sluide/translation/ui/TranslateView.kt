package dev.sluide.translation.ui

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.translation.session.TranslateModel
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox

/**
 * The Translate tab's content: the setup notice and the toolbar above the text on the left (editable) and the
 * translation on the right (read-only: caret, selection and copy work), both in the editor font ([editorCss], a CSS
 * style), without syntax colouring. It only shows [model] and forwards the user's input; it owns
 * [TranslateModel.onChange] until [dispose]. The right side's text is replaced only when the model's output changed,
 * so its caret and selection survive status and download ticks. The model decides what is too large to translate.
 */
internal class TranslateView(
    private val model: TranslateModel,
    editorCss: ObservableValue<String>,
    openScratch: (text: String, extension: String) -> Unit,
) {
    // Created with the model's text and output, so that a tab opened again has no undo step that empties a side.
    private val input = CodeEditors.text(model.text, highlighter = null)
    private val output = CodeEditors.text(model.output, highlighter = null).apply { editable = false }
    private val toolbar = TranslateToolbar(model, openScratch)
    private val notice = TranslateNotice(model)

    /** What the translation is doing, for the tab header's marker. */
    val activity = SimpleObjectProperty(ViewActivity.NONE)

    val node: Node =
        object : BorderPane() {
            override fun requestFocus() = input.focus()
        }.apply {
            styleClass += "translate-view"
            stylesheets += STYLESHEET
            top = VBox(notice.node, toolbar.node)
            center = SplitPane(input.node, output.node).apply { setDividerPositions(0.5) }
        }

    private var appliedText = model.text
    private var appliedOutput = model.outputVersion
    private var updating = false
    private val installed: () -> Unit = ::refresh

    init {
        input.node.styleProperty().bind(editorCss)
        output.node.styleProperty().bind(editorCss)
        // A listener, not subscribe: that would report the current text as an edit.
        input.textProperty.addListener { _, _, text -> if (!updating) typed(text) }
        model.onChange = installed
        refresh()
    }

    private fun typed(text: String) {
        model.edit(text)
        refresh()
    }

    private fun refresh() {
        if (model.text != appliedText) {
            // Text from outside (an editor or a file) or the echo of what was typed.
            appliedText = model.text
            if (model.text != input.text) {
                updating = true
                try {
                    input.replaceText(model.text)
                } finally {
                    updating = false
                }
            }
        }
        if (model.outputVersion != appliedOutput) {
            appliedOutput = model.outputVersion
            output.replaceText(model.output)
        }
        toolbar.show(model.status)
        notice.show(model.problem)
        activity.set(if (model.running) ViewActivity.RUNNING else ViewActivity.NONE)
    }

    /** The model stops notifying this view, unless a newer view took it over; the editors leave the font. */
    fun dispose() {
        if (model.onChange === installed) model.onChange = {}
        input.node.styleProperty().unbind()
        output.node.styleProperty().unbind()
    }

    companion object {
        val STYLESHEET: String =
            checkNotNull(
                TranslateView::class.java.getResource("/dev/sluide/translation/translate.css"),
            ).toExternalForm()
    }
}
