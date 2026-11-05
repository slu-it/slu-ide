package dev.sluide.speech.editor

import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.MAX_READ_BYTES
import dev.sluide.speech.isMarkdownName
import dev.sluide.speech.ui.ReadAloudModel
import dev.sluide.speech.ui.ReadAloudUi
import dev.sluide.speech.ui.ReadingBinding
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.util.Subscription

/**
 * Read aloud in place in one code [editor] (plain text or Markdown): the toolbar items, the setup notice as a banner
 * while there are problems, the tab marker, the highlight, the lock while reading and the keys. Owns
 * [ReadAloudModel.onChange] of [model] until [close]; [model] holds no text of its own. FX thread.
 */
internal class EditorReading(
    private val model: ReadAloudModel,
    private val editor: ExtensibleCodeEditor,
    controlsFor: (play: () -> Unit) -> EditorControls,
    stylesheet: String,
) : AutoCloseable {
    private val surface = EditorSurface(editor)
    private val binding = ReadingBinding(model, surface, ::play)
    private val controls = controlsFor(::play)
    private val activity = SimpleObjectProperty(ViewActivity.NONE)
    private var banner: AutoCloseable? = null
    private val installed: () -> Unit = ::refresh
    private val closers: List<AutoCloseable>

    init {
        val toolbar = editor.addToolbarItems(listOf(controls.toolbar))
        val css = editor.addStylesheet(stylesheet)
        val keys = editor.addEventFilter(KeyEvent.KEY_PRESSED) { event -> if (binding.onKey(event)) event.consume() }
        val clicks =
            editor.addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.clickCount == 2 && model.reading) binding.onDoubleClick()
            }
        val changes = editor.onTextChanged { if (model.reading) model.stop() }
        val activities = editor.addActivity(activity)
        closers = listOf(toolbar, css, keys.closer(), clicks.closer(), changes.closer(), activities.closer())
        model.onChange = installed
        refresh()
    }

    /** Whether this editor is reading (preparing, speaking or paused). */
    val reading: Boolean get() = model.reading

    /**
     * Reads from the sentence of the caret, or only the selection, of the editor's current text; refuses a selection,
     * or without one a text, over 1 MiB with a status.
     */
    fun play() {
        if (model.reading) return
        val selection = surface.selection
        val text = editor.text
        if ((selection?.let { it.last - it.first + 1 } ?: text.length) > MAX_READ_BYTES) {
            model.refuse("The text is larger than 1 MiB.")
            return
        }
        val name =
            editor.file.value
                ?.fileName
                ?.toString()
        model.playText(text, isMarkdownName(name), editor.caretOffset, selection)
    }

    private fun refresh() {
        val ui = model.ui
        binding.apply(ui)
        controls.show(ui)
        showNotice(ui)
        activity.set(ui.activity)
    }

    private fun showNotice(ui: ReadAloudUi) {
        if (ui.notice.isEmpty()) {
            banner?.close()
            banner = null
        } else if (banner == null) {
            banner = editor.addBanner(controls.notice)
        }
    }

    /** Stops a reading and removes everything added to the editor. */
    override fun close() {
        if (model.onChange === installed) model.onChange = {}
        model.stop()
        surface.editable = true
        editor.overlay(READ_ALOUD_LAYER, emptyList())
        banner?.close()
        banner = null
        closers.forEach { it.close() }
        controls.dispose()
    }

    private fun Subscription.closer() = AutoCloseable { unsubscribe() }
}
