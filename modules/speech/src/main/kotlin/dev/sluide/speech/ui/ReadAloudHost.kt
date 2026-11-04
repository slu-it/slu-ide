package dev.sluide.speech.ui

import dev.sluide.media.Pausable
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.Voice
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import javafx.beans.value.ObservableValue

/** One reading as the model needs it; a `ReadingSession` in the app, a fake in tests. [pause] is the [Pausable] one. */
internal interface Reading : Pausable {
    fun start()

    fun resume()

    fun stop()

    fun position(): ReadingPosition?
}

/**
 * What [ReadAloudModel] needs from the outside, so that it runs without the toolkit, an engine or a sound line. All
 * calls are made on the FX thread, and so are the callbacks, except that a reading's listener may be called on any
 * thread.
 */
internal interface ReadAloudHost {
    val voices: List<Voice>

    /** The voice id saved last, or null; may not exist any more. */
    val preferredVoiceId: String?

    val preferredSpeed: Double

    fun savePreferences(
        voice: Voice,
        speed: Double,
    )

    /** What keeps [voice]'s engine from speaking, checked off the FX thread; [result] runs on the FX thread. */
    fun checkProblems(
        voice: Voice,
        result: (List<SetupProblem>) -> Unit,
    )

    /** A reading of [chunks] from [startChunk]; not started. Its [listener] may be called on any thread. */
    fun newReading(
        voice: Voice,
        speed: Double,
        chunks: List<SpeechChunk>,
        startChunk: Int,
        listener: (ReadingState, String?) -> Unit,
    ): Reading

    /** Runs [task] on the FX thread. */
    fun runOnFx(task: Runnable)

    /** Calls [tick] repeatedly (about every 30 ms) on the FX thread until the result is closed. */
    fun poll(tick: Runnable): AutoCloseable

    /** [reading] plays now: pauses the other playback (the media player). */
    fun started(reading: Reading)

    /** [reading] is over. */
    fun disposed(reading: Reading)

    fun download(problem: SetupProblem.ModelMissing)

    fun cancelDownload(problem: SetupProblem.ModelMissing)

    /** The state of the download for [problem], or null for an engine that has none. */
    fun installState(problem: SetupProblem.ModelMissing): ObservableValue<InstallState>?
}
