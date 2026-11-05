package dev.sluide.speech.ui

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.localmodels.InstallState
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.Voice
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import dev.sluide.speech.text.Sentences
import dev.sluide.speech.text.SpeechText
import dev.sluide.speech.text.SpokenText
import javafx.beans.value.ObservableValue

private enum class Phase { IDLE, PREPARING, SPEAKING, PAUSED, FINISHED, FAILED }

/** The reading in progress: its text and chunks; [chunk] is the one being read. */
private class Run(
    val spoken: SpokenText,
    val chunks: List<SpeechChunk>,
    var chunk: Int,
) {
    lateinit var reading: Reading

    /** The distinct sentences, for "Sentence 3 of 40": a long first sentence is two chunks. */
    val sentenceStarts = chunks.map { it.sentenceStart }.distinct()
}

/**
 * The logic of the Read Aloud tab, without the toolkit (ADR 0084): the text, voice and speed of a window, the reading
 * and what the view shows of it ([ui]). FX thread only; the view owns [onChange] until it is disposed. The model
 * outlives the tab: closing the tab calls [stop].
 *
 * While a reading is set up, running or paused the text is read-only. Only the events of the current reading count:
 * a stopped or replaced one may still report a state late.
 */
internal class ReadAloudModel(
    private val host: ReadAloudHost,
) {
    var text: String = ""
        private set

    private var markdown = false
    private var textVersion = 0

    val voices: List<Voice> = host.voices

    var voice: Voice? = voices.firstOrNull { it.id == host.preferredVoiceId } ?: voices.firstOrNull()
        private set

    var speed: Double = host.preferredSpeed
        private set

    private var phase = Phase.IDLE
    private var failure: String? = null
    private var notice = emptyList<SetupProblem>()
    private var run: Run? = null
    private var position: ReadingPosition? = null
    private var polling: AutoCloseable? = null

    /** Counts the starts; the answer of an older start's check is dropped. */
    private var requests = 0

    /** Counts Check Again calls; the answer of an older one is dropped. */
    private var checks = 0

    /** Whether a start is waiting for its setup check; Check Again leaves the notice alone then. */
    private var starting = false

    /** Called after [ui] changed, on the FX thread. */
    var onChange: () -> Unit = {}

    var ui: ReadAloudUi = compute()
        private set

    /** Puts [text] in (markdown if [markdown]), stops a reading, and starts reading at once unless setup is missing. */
    fun read(
        text: String,
        markdown: Boolean,
    ) {
        stopRun()
        this.text = text
        this.markdown = markdown
        textVersion++
        phase = Phase.IDLE
        failure = null
        if (text.isBlank()) publish() else begin(0)
    }

    /** Starts reading at the sentence of the shown offset [caret]; the first one if it is past the end. */
    fun play(caret: Int) {
        if (reading() || voice == null || text.isBlank()) return
        begin(caret)
    }

    /** Pauses a preparing or speaking reading, resumes a paused one. */
    fun togglePause() {
        val run = run ?: return
        if (phase == Phase.PAUSED) {
            run.reading.resume()
        } else if (reading()) {
            run.reading.pause()
        }
    }

    /** Ends a reading; the text is editable again. */
    fun stop() {
        if (!reading()) return
        stopRun()
        phase = Phase.IDLE
        failure = null
        publish()
    }

    /** Restarts the reading at the sentence of the shown offset [offset]; nothing when no reading runs. */
    fun jumpTo(offset: Int) {
        val run = run ?: return
        restart(run, chunkAt(run.spoken, run.chunks, offset))
    }

    fun setVoice(voice: Voice) {
        if (voice == this.voice) return
        this.voice = voice
        host.savePreferences(voice, speed)
        restartAtCurrent()
        publish()
    }

    fun setSpeed(speed: Double) {
        if (speed == this.speed) return
        this.speed = speed
        voice?.let { host.savePreferences(it, speed) }
        restartAtCurrent()
        publish()
    }

    /** The user edited the text (only while it is editable). */
    fun textEdited(text: String) {
        if (reading() || text == this.text) return
        this.text = text
        markdown = false
        if (phase == Phase.FINISHED || phase == Phase.FAILED) {
            phase = Phase.IDLE
            failure = null
        }
        publish()
    }

    /** Looks for setup problems again; nothing starts. */
    fun checkAgain() {
        val voice = voice ?: return
        if (starting) return
        val check = ++checks
        host.checkProblems(voice) { problems ->
            if (check != checks || starting) return@checkProblems
            notice = problems
            publish()
        }
    }

    fun download() {
        missingModel()?.let(host::download)
    }

    fun cancelDownload() {
        missingModel()?.let(host::cancelDownload)
    }

    /** The download state of the missing model in the notice, if there is one. */
    fun installState(): ObservableValue<InstallState>? = missingModel()?.let(host::installState)

    private fun missingModel() = notice.filterIsInstance<SetupProblem.ModelMissing>().firstOrNull()

    private fun reading() = phase == Phase.PREPARING || phase == Phase.SPEAKING || phase == Phase.PAUSED

    private fun begin(shownOffset: Int) {
        val voice = voice ?: return
        stopRun()
        val request = ++requests
        starting = true
        phase = Phase.PREPARING
        failure = null
        publish()
        host.checkProblems(voice) { problems ->
            if (request != requests) return@checkProblems
            starting = false
            notice = problems
            if (problems.isEmpty()) {
                launchAt(shownOffset)
            } else {
                phase = Phase.IDLE
                publish()
            }
        }
    }

    private fun launchAt(shownOffset: Int) {
        val spoken = SpeechText.prepare(text, markdown)
        val chunks = Sentences.chunks(spoken)
        if (chunks.isEmpty()) {
            phase = Phase.FAILED
            failure = "Nothing to read."
            publish()
            return
        }
        launch(Run(spoken, chunks, chunkAt(spoken, chunks, shownOffset)))
    }

    private fun launch(run: Run) {
        val voice = voice ?: return
        this.run = run
        position = null
        phase = Phase.PREPARING
        run.reading =
            host.newReading(voice, speed, run.chunks, run.chunk) { state, message ->
                host.runOnFx { onEvent(run, state, message) }
            }
        publish()
        run.reading.start()
    }

    private fun onEvent(
        run: Run,
        state: ReadingState,
        message: String?,
    ) {
        if (this.run !== run) return
        when (state) {
            ReadingState.PREPARING -> {
                phase = Phase.PREPARING
            }

            ReadingState.SPEAKING -> {
                phase = Phase.SPEAKING
                host.started(run.reading)
                startPolling()
            }

            ReadingState.PAUSED -> {
                phase = Phase.PAUSED
                startPolling()
            }

            ReadingState.FINISHED -> {
                end(run, Phase.FINISHED, null)
            }

            ReadingState.STOPPED -> {
                end(run, Phase.IDLE, null)
            }

            ReadingState.FAILED -> {
                end(run, Phase.FAILED, message ?: "Reading failed.")
            }
        }
        publish()
    }

    private fun end(
        run: Run,
        phase: Phase,
        failure: String?,
    ) {
        finish(run)
        this.phase = phase
        this.failure = failure
    }

    private fun startPolling() {
        if (polling == null) polling = host.poll(::tick)
    }

    private fun tick() {
        val run = run ?: return
        val now = run.reading.position() ?: return
        if (now == position) return
        position = now
        run.chunk = now.chunkIndex
        publish()
    }

    /** Stops the running reading, if any, and drops the answers of pending starts. */
    private fun stopRun() {
        requests++
        starting = false
        run?.let {
            it.reading.stop()
            finish(it)
        }
    }

    private fun finish(run: Run) {
        host.disposed(run.reading)
        polling?.close()
        polling = null
        position = null
        if (this.run === run) this.run = null
    }

    private fun restartAtCurrent() {
        val run = run ?: return
        restart(run, run.chunk)
    }

    private fun restart(
        run: Run,
        chunk: Int,
    ) {
        val wasPaused = phase == Phase.PAUSED
        run.reading.stop()
        finish(run)
        requests++
        launch(Run(run.spoken, run.chunks, chunk))
        // The new reading holds its start until resumed: a paused reading stays paused.
        if (wasPaused) this.run?.reading?.pause()
    }

    private fun chunkAt(
        spoken: SpokenText,
        chunks: List<SpeechChunk>,
        shownOffset: Int,
    ): Int {
        val offset = spoken.spokenOffset(shownOffset.coerceAtLeast(0))
        // A caret at the end (or past the last chunk) starts over at the first chunk; an offset at the end of a chunk
        // still belongs to it, as a double-click on the last word of a sentence leaves the caret there.
        if (offset >= spoken.text.length) return 0
        return chunks.indexOfFirst { it.end >= offset }.coerceAtLeast(0)
    }

    private fun publish() {
        val next = compute()
        if (next == ui) return
        ui = next
        onChange()
    }

    private fun compute(): ReadAloudUi {
        val reading = reading()
        val ranges = highlight()
        return ReadAloudUi(
            textVersion = textVersion,
            editable = !reading,
            playMode =
                when (phase) {
                    Phase.PAUSED -> PlayMode.RESUME
                    Phase.PREPARING, Phase.SPEAKING -> PlayMode.PAUSE
                    else -> PlayMode.PLAY
                },
            playEnabled = if (reading) run != null else voice != null && text.isNotBlank(),
            stopEnabled = reading,
            status = status(),
            activity = if (reading) ViewActivity.RUNNING else ViewActivity.NONE,
            overlay = ranges,
            reveal = ranges.lastOrNull()?.let { it.start until it.end },
            notice = notice,
            voice = voice,
            speed = speed,
        )
    }

    /** The sentence, then the word, in shown offsets; empty without a position. */
    private fun highlight(): List<StyledRange> {
        val run = run ?: return emptyList()
        val at = position ?: return emptyList()
        val spoken = run.spoken
        val sentence =
            StyledRange(spoken.sourceOffset(at.sentenceStart), spoken.sourceEnd(at.sentenceEnd), SENTENCE_STYLE)
        val wordStart = at.wordStart
        val wordEnd = at.wordEnd
        if (wordStart == null || wordEnd == null) return listOf(sentence)
        return listOf(
            sentence,
            StyledRange(spoken.sourceOffset(wordStart), spoken.sourceEnd(wordEnd), WORD_STYLE),
        )
    }

    private fun status(): String =
        when (phase) {
            Phase.IDLE -> {
                ""
            }

            Phase.PREPARING -> {
                "Preparing…"
            }

            Phase.PAUSED -> {
                "Paused"
            }

            Phase.FINISHED -> {
                "Finished"
            }

            Phase.FAILED -> {
                failure.orEmpty()
            }

            Phase.SPEAKING -> {
                val run = run
                val start = position?.sentenceStart ?: run?.chunks?.getOrNull(run.chunk)?.sentenceStart
                val number = (run?.sentenceStarts?.indexOf(start) ?: 0).coerceAtLeast(0) + 1
                "Sentence $number of ${run?.sentenceStarts?.size ?: 0}"
            }
        }
}
