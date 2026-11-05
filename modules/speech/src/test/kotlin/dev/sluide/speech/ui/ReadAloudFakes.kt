package dev.sluide.speech.ui

import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.Voice
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue

internal val HEART = Voice("af_heart", "Heart", "en-US", Gender.FEMALE, "A", "fake")
internal val GEORGE = Voice("bm_george", "George", "en-GB", Gender.MALE, "C", "fake")
internal val EMMA = Voice("bf_emma", "Emma", "en-GB", Gender.FEMALE, "B-", "fake")

/** A reading the test drives: [emit] reports a state as the session's worker thread would, [at] sets the position. */
internal class FakeReading(
    val voice: Voice,
    val speed: Double,
    val chunks: List<SpeechChunk>,
    val startChunk: Int,
    private val listener: (ReadingState, String?) -> Unit,
) : Reading {
    val calls = mutableListOf<String>()
    var position: ReadingPosition? = null

    override fun start() {
        calls += "start"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun position() = position

    fun emit(
        state: ReadingState,
        message: String? = null,
    ) = listener(state, message)
}

/** A host without engine, sound or toolkit; a check answers at once with [problems] unless [holdChecks] is set. */
internal class FakeHost(
    override val voices: List<Voice> = listOf(HEART, EMMA, GEORGE),
    override val preferredVoiceId: String? = null,
    override val preferredSpeed: Double = 1.0,
) : ReadAloudHost {
    var problems: List<SetupProblem> = emptyList()
    var holdChecks = false
    var checks = 0
    val held = mutableListOf<(List<SetupProblem>) -> Unit>()
    val readings = mutableListOf<FakeReading>()
    val saved = mutableListOf<Pair<String, Double>>()
    val events = mutableListOf<String>()
    var pollTick: Runnable? = null
    var pollClosed = 0
    val state = SimpleObjectProperty<InstallState>(InstallState.NotInstalled)

    val reading get() = readings.last()

    override fun savePreferences(
        voice: Voice,
        speed: Double,
    ) {
        saved += voice.id to speed
    }

    override fun checkProblems(
        voice: Voice,
        result: (List<SetupProblem>) -> Unit,
    ) {
        checks++
        if (holdChecks) held += result else result(problems)
    }

    override fun newReading(
        voice: Voice,
        speed: Double,
        chunks: List<SpeechChunk>,
        startChunk: Int,
        listener: (ReadingState, String?) -> Unit,
    ): Reading = FakeReading(voice, speed, chunks, startChunk, listener).also { readings += it }

    /** Runs the reading's events; at once, or on the FX thread in UI tests. */
    var onFx: (Runnable) -> Unit = { it.run() }

    override fun runOnFx(task: Runnable) = onFx(task)

    override fun poll(tick: Runnable): AutoCloseable {
        pollTick = tick
        return AutoCloseable { pollClosed++ }
    }

    override fun started(reading: Reading) {
        events += "started"
    }

    override fun disposed(reading: Reading) {
        events += "disposed"
    }

    override fun download(problem: SetupProblem.ModelMissing) {
        events += "download ${problem.variant}"
    }

    override fun cancelDownload(problem: SetupProblem.ModelMissing) {
        events += "cancel ${problem.variant}"
    }

    override fun installState(problem: SetupProblem.ModelMissing): ObservableValue<InstallState> = state
}

internal val MODEL_MISSING = SetupProblem.ModelMissing("kokoro", "FP32", 325_505_369)
