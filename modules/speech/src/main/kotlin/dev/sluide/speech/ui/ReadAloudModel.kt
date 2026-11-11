package dev.sluide.speech.ui

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.localmodels.InstallState
import dev.sluide.speech.MAX_READ_BYTES
import dev.sluide.speech.ReadingText
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

/**
 * The reading in progress: its text and chunks; [chunk] is the one being read. The text is the shown text from [base] on,
 * [length] chars of it (the selection, or everything): all offsets of [spoken] are relative to [base].
 */
private class Run(
    val spoken: SpokenText,
    val chunks: List<SpeechChunk>,
    var chunk: Int,
    val base: Int,
    val length: Int,
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
 * With [ownsText] (the tool tab) the model holds the text and [play] reads it. An editor holds its own text instead and
 * calls [playText] (another module's surface calls [playReading]); the model's [text] stays empty then, so
 * [ownsText] = false makes Play enabled whenever a voice exists and the caller guards an empty text (reading a blank
 * text still ends in "Nothing to read.").
 *
 * While a reading is set up, running or paused the text is read-only. Only the events of the current reading count:
 * a stopped or replaced one may still report a state late.
 */
internal class ReadAloudModel(
    private val host: ReadAloudHost,
    private val ownsText: Boolean = true,
) {
    var text: String = ""
        private set

    private var markdown = false
    private var textVersion = 0

    val voices: List<Voice> = host.voices

    var voice: Voice? = preferredVoice()
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
        if (text.isBlank()) publish() else begin(text, markdown, 0, null)
    }

    /**
     * Starts reading the model's own text: from the sentence of the shown offset [caret] (the first one if it is past
     * the end), or only [selection] (`start until end` in shown offsets; null or empty means none).
     */
    fun play(
        caret: Int,
        selection: IntRange? = null,
    ) {
        if (reading || voice == null || text.isBlank()) return
        begin(text, markdown, caret, selection)
    }

    /** As [play] for a [text] the caller holds (an editor); leaves [ReadAloudModel.text] and its version alone. */
    fun playText(
        text: String,
        markdown: Boolean,
        caret: Int,
        selection: IntRange? = null,
    ) {
        if (reading || voice == null) return
        begin(text, markdown, caret, selection)
    }

    /**
     * Reads the plain [text] of another module's surface from the sentence that contains its start (a sentence that
     * ends right at it is over) up to its end, without its skip ranges; with [paused] the reading holds its start until
     * resumed. Refuses a range over 1 MiB with a status. A range with nothing to speak from its start on is "Finished"
     * at once, so that a reading that goes on text after text (chapters) skips it.
     */
    fun playReading(
        text: ReadingText,
        paused: Boolean = false,
    ) {
        if (reading || voice == null) return
        val end = text.end.coerceIn(0, text.shown.length)
        val start = text.start.coerceIn(0, end)
        if (end - start > MAX_READ_BYTES) return refuse("The text is larger than 1 MiB.")
        // Prepared from the paragraph of the start on: the sentences from the start come out as with the whole text.
        val cut = SpeechText.paragraphStart(text.shown, start, text.skip)
        val skip = text.skip.map { (it.first.coerceAtLeast(cut) - cut)..(it.last.coerceAtMost(end - 1) - cut) }
        begin(text.shown.substring(cut, end), cut, start - cut, fromRangeStart = true, paused) {
            SpeechText.preparePlain(it, skip)
        }
    }

    /** Shows [message] as a failed status without reading (an editor's text is too large); ignored while reading. */
    fun refuse(message: String) {
        if (reading) return
        phase = Phase.FAILED
        failure = message
        publish()
    }

    /** Pauses a preparing or speaking reading, resumes a paused one. */
    fun togglePause() {
        val run = run ?: return
        if (phase == Phase.PAUSED) {
            run.reading.resume()
        } else if (reading) {
            run.reading.pause()
        }
    }

    /** Ends a reading; the text is editable again. */
    fun stop() {
        if (!reading) return
        stopRun()
        phase = Phase.IDLE
        failure = null
        publish()
    }

    /**
     * Restarts the reading at the sentence of the shown offset [offset]; nothing when no reading runs or [offset] is
     * outside the text being read (prepared). Returns whether it restarted.
     */
    fun jumpTo(offset: Int): Boolean {
        val run = run ?: return false
        if (offset < run.base || offset >= run.base + run.length) return false
        restart(run, chunkAt(run.spoken, run.chunks, offset - run.base))
        return true
    }

    private fun preferredVoice() = voices.firstOrNull { it.id == host.preferredVoiceId } ?: voices.firstOrNull()

    /** While idle, takes the voice and speed another model (an editor, the tab) may have chosen since. */
    private fun takePreferences() {
        if (reading) return
        voice = preferredVoice()
        speed = host.preferredSpeed
    }

    fun setVoice(voice: Voice) {
        takePreferences()
        if (voice == this.voice) return publish()
        this.voice = voice
        host.savePreferences(voice, speed)
        restartAtCurrent()
        publish()
    }

    fun setSpeed(speed: Double) {
        takePreferences()
        if (speed == this.speed) return publish()
        this.speed = speed
        voice?.let { host.savePreferences(it, speed) }
        restartAtCurrent()
        publish()
    }

    /** The user edited the text (only while it is editable). */
    fun textEdited(text: String) {
        if (reading || text == this.text) return
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

    /** Whether a reading is being prepared, spoken or paused. */
    val reading: Boolean
        get() = phase == Phase.PREPARING || phase == Phase.SPEAKING || phase == Phase.PAUSED

    /** Whether the last reading reached its end on its own ("Finished"), not after a stop or an error. */
    val finished: Boolean
        get() = phase == Phase.FINISHED

    private fun begin(
        source: String,
        markdown: Boolean,
        caret: Int,
        selection: IntRange?,
    ) {
        val selected =
            selection
                ?.let { it.first.coerceAtLeast(0)..it.last.coerceAtMost(source.length - 1) }
                ?.takeUnless { it.isEmpty() }
        val base = selected?.first ?: 0
        val slice = if (selected == null) source else source.substring(selected)
        // The whole selection is read, from its start; without one the reading starts at the caret's sentence.
        val shownOffset = if (selected == null) caret else 0
        begin(slice, base, shownOffset, fromRangeStart = false) { SpeechText.prepare(it, markdown) }
    }

    /**
     * Checks the setup, then reads [slice] (the shown text from [base] on, prepared by [prepare]) from the sentence of
     * [shownOffset] (relative to [base]); with [paused] the reading holds its start. A caret ([fromRangeStart] false)
     * at the end of a sentence reads it, and one with nothing to speak from it on starts at the first sentence. A
     * range start skips a sentence that ends at it, and with nothing to speak from it on the reading is finished.
     */
    private fun begin(
        slice: String,
        base: Int,
        shownOffset: Int,
        fromRangeStart: Boolean,
        paused: Boolean = false,
        prepare: (String) -> SpokenText,
    ) {
        takePreferences()
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
                launchAt(prepare(slice), base, slice.length, shownOffset, fromRangeStart, paused)
            } else {
                phase = Phase.IDLE
                publish()
            }
        }
    }

    private fun launchAt(
        spoken: SpokenText,
        base: Int,
        length: Int,
        shownOffset: Int,
        fromRangeStart: Boolean,
        paused: Boolean,
    ) {
        val chunks = Sentences.chunks(spoken)
        val first =
            when {
                chunks.isEmpty() -> null
                fromRangeStart -> rangeChunk(spoken, chunks, shownOffset)
                else -> chunkAt(spoken, chunks, shownOffset)
            }
        if (first == null) {
            phase = if (fromRangeStart) Phase.FINISHED else Phase.FAILED
            failure = if (fromRangeStart) null else "Nothing to read."
            publish()
            return
        }
        val run = Run(spoken, chunks, first, base, length)
        launch(run)
        // As in a restart: the new reading holds its start until resumed.
        if (paused) run.reading.pause()
    }

    /** The chunk a range starting at [shownOffset] begins with: not one that ends there; null when none is left. */
    private fun rangeChunk(
        spoken: SpokenText,
        chunks: List<SpeechChunk>,
        shownOffset: Int,
    ): Int? {
        val offset = spoken.spokenOffset(shownOffset.coerceAtLeast(0))
        return chunks.indexOfFirst { it.end > offset }.takeIf { it >= 0 }
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
        launch(Run(run.spoken, run.chunks, chunk, run.base, run.length))
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
        val reading = this.reading
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
            playEnabled = if (reading) run != null else voice != null && (!ownsText || text.isNotBlank()),
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
            StyledRange(
                run.base + spoken.sourceOffset(at.sentenceStart),
                run.base + spoken.sourceEnd(at.sentenceEnd),
                SENTENCE_STYLE,
            )
        val wordStart = at.wordStart
        val wordEnd = at.wordEnd
        if (wordStart == null || wordEnd == null) return listOf(sentence)
        return listOf(
            sentence,
            StyledRange(run.base + spoken.sourceOffset(wordStart), run.base + spoken.sourceEnd(wordEnd), WORD_STYLE),
        )
    }

    private fun status(): String =
        when (phase) {
            Phase.IDLE -> {
                if (notice.isEmpty()) "" else "Setup needed."
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
