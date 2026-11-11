package dev.sluide.ebooks.view

import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.ReadAloud
import dev.sluide.speech.ReadAloudControls
import dev.sluide.speech.ReadAloudSurface
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.input.KeyEvent

/** The part of Read Aloud's `ReadAloudControls` the reader uses; tests pass a fake (ADR 0164). */
internal interface ReaderControls {
    val toolbar: Node
    val notice: Node
    val activity: ObservableValue<ViewActivity>
    val reading: Boolean
    var onFinished: () -> Unit

    fun play()

    fun stop()

    fun jumpTo(offset: Int)

    fun handleKey(event: KeyEvent): Boolean

    fun dispose()
}

/** The production [ReaderControls]: forwards to [controls]. */
internal class SpeechReaderControls(
    private val controls: ReadAloudControls,
) : ReaderControls {
    override val toolbar get() = controls.toolbar
    override val notice get() = controls.notice
    override val activity get() = controls.activity
    override val reading get() = controls.reading
    override var onFinished: () -> Unit
        get() = controls.onFinished
        set(value) {
            controls.onFinished = value
        }

    override fun play() = controls.play()

    override fun stop() = controls.stop()

    override fun jumpTo(offset: Int) = controls.jumpTo(offset)

    override fun handleKey(event: KeyEvent) = controls.handleKey(event)

    override fun dispose() = controls.dispose()
}

/** Creates the controls over a surface; null where the window has no Read Aloud. */
internal typealias ControlsFactory = (ReadAloudSurface) -> ReaderControls?

/** The default factory: the window's [ReadAloud], looked up through the host of [view]. */
internal fun windowControls(view: EpubFileView): ControlsFactory =
    { surface ->
        view.host
            ?.windowService(ReadAloud::class.java)
            ?.controls(surface)
            ?.let(::SpeechReaderControls)
    }
