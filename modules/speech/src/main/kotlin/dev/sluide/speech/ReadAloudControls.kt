package dev.sluide.speech

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.editor.EditorControls
import dev.sluide.speech.ui.PlayMode
import dev.sluide.speech.ui.ReadAloudModel
import dev.sluide.speech.ui.ReadingBinding
import dev.sluide.speech.ui.ReadingSurface
import dev.sluide.speech.ui.SENTENCE_STYLE
import dev.sluide.speech.ui.WORD_STYLE
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.input.KeyEvent

/**
 * Read Aloud over another module's [surface] (ADR 0164): the toolbar and the setup notice for the caller to place, and
 * the reading, whose lock and highlight go to the surface. Each one has its own reading on the window's shared host:
 * voice and speed are the shared preferences, and starting a reading pauses the running one app-wide. Owns
 * [ReadAloudModel.onChange] of its model until [dispose]. FX thread only; create it with [ReadAloud.controls].
 */
class ReadAloudControls internal constructor(
    private val model: ReadAloudModel,
    private val surface: ReadAloudSurface,
    controlsFor: (play: () -> Unit) -> EditorControls,
) {
    private val binding = ReadingBinding(model, SurfaceAdapter(surface), ::play)
    private val controls = controlsFor(::play)
    private val activityProperty = SimpleObjectProperty(ViewActivity.NONE)
    private val installed: () -> Unit = ::refresh
    private var wasReading = false

    /** The tool's toolbar (Play/Pause, Stop, voice, speed, status), embedded style, with `speech.css`. */
    val toolbar: Node = controls.toolbar

    /** The setup notice (no model, no espeak-ng); not visible and not managed while nothing is missing. */
    val notice: Node = controls.notice

    /** [ViewActivity.RUNNING] while reading, else [ViewActivity.NONE]: for the running marker of the caller's tab. */
    val activity: ObservableValue<ViewActivity> = activityProperty

    /** Whether a reading is being prepared, spoken or paused. */
    val reading: Boolean get() = model.reading

    /**
     * Called once when a reading reached [ReadingText.end] on its own, and at once for a [ReadingText] with nothing to
     * speak from its start on (an empty chapter); not after [stop] or an error. The reading is over when it is called,
     * so it may [play] the next text (the next chapter).
     */
    var onFinished: () -> Unit = {}

    /** The text of the last [play], for a [jumpTo] before the part of it that was prepared. */
    private var lastText: ReadingText? = null

    init {
        model.onChange = installed
        refresh()
    }

    /** Asks the surface for its [ReadingText] and reads it; nothing while reading. */
    fun play() {
        if (model.reading) return
        val text = surface.readingText() ?: return model.refuse("Nothing to read.")
        lastText = text
        model.playReading(text)
    }

    /** Pauses a preparing or speaking reading, resumes a paused one. */
    fun togglePause() = model.togglePause()

    fun stop() = model.stop()

    /**
     * While reading, restarts at the sentence of [offset] in [ReadingText.shown], anywhere in `[0, end)` of the last
     * [ReadingText] (also before its start); a paused reading stays paused. Nothing while idle or outside that range.
     */
    fun jumpTo(offset: Int) {
        if (!model.reading || model.jumpTo(offset)) return
        val last = lastText ?: return
        if (offset < 0 || offset >= last.end.coerceAtMost(last.shown.length)) return
        // Before the paragraph the reading prepared: a new reading of the same text from there on.
        val paused = model.ui.playMode == PlayMode.RESUME
        model.stop()
        model.playReading(ReadingText(last.shown, offset, last.end, last.skip), paused)
    }

    /**
     * Shortcut+Enter plays while idle; Space pauses or resumes and Escape stops while reading. Returns whether the
     * key was handled, so the caller consumes it.
     */
    fun handleKey(event: KeyEvent): Boolean = binding.onKey(event)

    /** Stops a reading (clearing the highlight and unlocking the surface) and detaches from the model. */
    fun dispose() {
        model.stop()
        if (model.onChange === installed) model.onChange = {}
        controls.dispose()
    }

    private fun refresh() {
        val ui = model.ui
        binding.apply(ui)
        controls.show(ui)
        val problems = ui.notice.isNotEmpty()
        notice.isVisible = problems
        notice.isManaged = problems
        activityProperty.set(ui.activity)
        // Last, with everything applied: the callback may start the next reading, which refreshes again.
        val finished = wasReading && !model.reading && model.finished
        wasReading = model.reading
        if (finished) onFinished()
    }
}

/** The [ReadingSurface] the binding drives, forwarding to a [ReadAloudSurface]; the lock only on changes. */
private class SurfaceAdapter(
    private val surface: ReadAloudSurface,
) : ReadingSurface {
    // Only a double-click reads these (ReadingBinding.onDoubleClick); the caller jumps with ReadAloudControls.jumpTo.
    override val caretOffset = 0
    override val selection: IntRange? = null

    override var editable = true
        set(value) {
            if (value == field) return
            field = value
            surface.lock(!value)
        }

    override fun overlay(ranges: List<StyledRange>) =
        surface.highlight(ranges.of(SENTENCE_STYLE), ranges.of(WORD_STYLE))

    override fun reveal(
        start: Int,
        end: Int,
    ) = surface.reveal(start, end)

    private fun List<StyledRange>.of(style: String) =
        firstOrNull { it.styleClass == style }?.let {
            it.start until
                it.end
        }
}
