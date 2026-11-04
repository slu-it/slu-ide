package dev.sluide.speech.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.media.PlaybackCoordinator
import dev.sluide.speech.SpeechPreferences
import dev.sluide.speech.audio.AudioSink
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.SpeechEngines
import dev.sluide.speech.engine.Voice
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.ModelInstaller
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingSession
import dev.sluide.speech.session.ReadingState
import javafx.beans.value.ObservableValue
import java.util.concurrent.Executor

private const val KOKORO = "kokoro"

/** A [Reading] over a [ReadingSession]. */
private class SessionReading(
    private val session: ReadingSession,
) : Reading {
    override fun start() = session.start()

    override fun pause() = session.pause()

    override fun resume() = session.resume()

    override fun stop() = session.stop()

    override fun position(): ReadingPosition? = session.position()
}

/**
 * The [ReadAloudHost] of a window: the engines, the saved voice and speed, the one-playback rule and the model
 * download. [background] runs the setup check, [dispatch] hands results to the FX thread, [poller] makes the repeating
 * position tick, [sink] opens a new audio sink for each reading.
 */
internal class LiveReadAloudHost(
    private val engines: SpeechEngines,
    private val preferences: SpeechPreferences,
    private val coordinator: PlaybackCoordinator,
    private val installer: ModelInstaller,
    private val tasks: () -> BackgroundTasks?,
    private val sink: () -> AudioSink,
    private val dispatch: (Runnable) -> Unit,
    private val poller: (Runnable) -> AutoCloseable,
    private val background: Executor,
) : ReadAloudHost {
    override val voices: List<Voice> get() = engines.voices

    override val preferredVoiceId: String? get() = preferences.voiceId

    override val preferredSpeed: Double get() = preferences.speed

    override fun savePreferences(
        voice: Voice,
        speed: Double,
    ) = preferences.save(voice.id, speed)

    override fun checkProblems(
        voice: Voice,
        result: (List<SetupProblem>) -> Unit,
    ) {
        background.execute {
            val problems =
                try {
                    engines.engineFor(voice).problems()
                } catch (e: Exception) {
                    listOf(SetupProblem.Broken(e.message ?: "The speech engine does not work."))
                }
            dispatch(Runnable { result(problems) })
        }
    }

    override fun newReading(
        voice: Voice,
        speed: Double,
        chunks: List<SpeechChunk>,
        startChunk: Int,
        listener: (ReadingState, String?) -> Unit,
    ): Reading =
        SessionReading(
            ReadingSession(engines.engineFor(voice), voice, speed, chunks, startChunk, sink(), listener),
        )

    override fun runOnFx(task: Runnable) = dispatch(task)

    override fun poll(tick: Runnable): AutoCloseable = poller(tick)

    override fun started(reading: Reading) = coordinator.started(reading)

    override fun disposed(reading: Reading) = coordinator.disposed(reading)

    override fun download(problem: SetupProblem.ModelMissing) {
        variant(problem)?.let { installer.download(it, tasks()) }
    }

    override fun cancelDownload(problem: SetupProblem.ModelMissing) {
        variant(problem)?.let(installer::cancel)
    }

    override fun installState(problem: SetupProblem.ModelMissing): ObservableValue<InstallState>? =
        variant(problem)?.let(installer::state)

    private fun variant(problem: SetupProblem.ModelMissing): KokoroVariant? =
        if (problem.engineId == KOKORO) KokoroVariant.entries.firstOrNull { it.name == problem.variant } else null
}
