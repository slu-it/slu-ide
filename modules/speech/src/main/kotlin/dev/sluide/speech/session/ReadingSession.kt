package dev.sluide.speech.session

import dev.sluide.media.Pausable
import dev.sluide.speech.audio.AudioSink
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.SpeechEngine
import dev.sluide.speech.engine.SpeechException
import dev.sluide.speech.engine.Voice
import dev.sluide.speech.text.Sentences
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

private val log = System.getLogger(ReadingSession::class.java.name)

/**
 * One reading (ADR 0124): reads [chunks] from [startChunk] on, the first of them split by
 * [Sentences.splitForFastStart] for fast first audio. A synthesis thread (`sluide-speech-synth`) synthesizes the pieces
 * ahead with [engine]; a player thread (`sluide-speech-play`) plays them through [sink] once [StartRule] allows, and
 * closes the sink at the end. Both are platform daemon threads: the engine's native calls would pin a virtual thread.
 *
 * [listener] gets every state change in order, on a thread of its own (`sluide-speech-events`), the error text with
 * [ReadingState.FAILED]; nothing after a final state. A [SpeechException] fails the reading with its message, any
 * other failure with "Reading failed: <exception class>" (logged).
 *
 * [start], [pause], [resume] and [stop] may be called from any thread, are idempotent and do not block. The session
 * does not take part in the `PlaybackCoordinator` itself; it is [Pausable] so that its owner can register it.
 */
class ReadingSession internal constructor(
    engine: SpeechEngine,
    voice: Voice,
    speed: Double,
    chunks: List<SpeechChunk>,
    startChunk: Int,
    private val sink: AudioSink,
    private val listener: (ReadingState, String?) -> Unit,
    nanoTime: () -> Long,
    private val newThread: (name: String, task: Runnable) -> Thread,
    lookaheadSeconds: Double,
    pollInterval: Duration,
) : Pausable {
    constructor(
        engine: SpeechEngine,
        voice: Voice,
        speed: Double,
        chunks: List<SpeechChunk>,
        startChunk: Int,
        sink: AudioSink,
        listener: (ReadingState, String?) -> Unit,
    ) : this(
        engine,
        voice,
        speed,
        chunks,
        startChunk,
        sink,
        listener,
        System::nanoTime,
        ::platformThread,
        LOOKAHEAD_SECONDS,
        POLL_INTERVAL,
    )

    private val core =
        SessionCore(pieces(chunks, startChunk), lookaheadSeconds, pollInterval.toNanos()) { sink.playedFrames }
    private val synthesis = SynthesisLoop(core, engine, voice, speed, nanoTime, ::failed)
    private val player = PlayerLoop(core, sink, Control())
    private val threads = mutableListOf<Thread>()
    private val events = LinkedBlockingQueue<Pair<ReadingState, String?>>()
    private var eventThread: Thread? = null
    private val sinkClosed = AtomicBoolean()

    @Volatile
    private var current = ReadingState.PREPARING

    /**
     * Where the reading stands; [ReadingState.PREPARING] also before [start]. Any thread. The [listener] may get a
     * state later than it was set here.
     */
    val state: ReadingState get() = current

    /** Starts synthesis and the player; [ReadingState.PREPARING] until the start rule allows playing. */
    fun start() {
        core.lock.withLock {
            if (core.started || core.ended) return
            core.started = true
            emit(ReadingState.PREPARING)
            threads += newThread(SYNTHESIS_THREAD, synthesis)
            threads += newThread(PLAYER_THREAD, player)
            threads.forEach { it.start() }
        }
    }

    /**
     * Pauses while preparing or speaking: the sink stops, synthesis goes on filling the lookahead. Paused while
     * preparing, the reading does not start before [resume]. Does nothing before [start] and after the end.
     */
    override fun pause() {
        core.lock.withLock {
            if (!core.started || core.ended || core.paused) return
            core.paused = true
            if (core.playing) sink.pause()
            emit(ReadingState.PAUSED)
        }
    }

    /** Continues a paused reading: [ReadingState.SPEAKING] if it was playing, else [ReadingState.PREPARING] again. */
    fun resume() {
        core.lock.withLock {
            if (!core.paused || core.ended) return
            core.paused = false
            if (core.playing) {
                sink.start()
                emit(ReadingState.SPEAKING)
            } else {
                emit(ReadingState.PREPARING)
            }
            core.changed.signalAll()
        }
    }

    /** Ends the reading ([ReadingState.STOPPED]): interrupts the threads and drops buffered audio; also before [start]. */
    fun stop() {
        if (end(ReadingState.STOPPED, null)) release()
    }

    /** What is spoken now; null before the first audio plays and after the end. Any thread, lock-free. */
    fun position(): ReadingPosition? {
        if (!core.playing || core.ended) return null
        return positionAt(core.placed, core.placedCount, sink.playedFrames)
    }

    private fun failed(error: Throwable) {
        val message =
            if (error is SpeechException) {
                error.message
            } else {
                "Reading failed: ${error.javaClass.simpleName}"
            }
        if (end(ReadingState.FAILED, message)) {
            if (error !is SpeechException) log.log(System.Logger.Level.ERROR, "Reading failed", error)
            release()
        }
    }

    /** Moves to the final [state]; false if the reading had ended already. */
    private fun end(
        state: ReadingState,
        message: String?,
    ): Boolean =
        core.lock.withLock {
            if (core.ended) return false
            emit(state, message)
            core.ended = true
            core.changed.signalAll()
            true
        }

    /** After the end: wakes blocked sink calls, interrupts the other threads; the player closes the sink. */
    private fun release() {
        val running = core.lock.withLock { threads.toList() }
        running.filter { it !== Thread.currentThread() }.forEach { it.interrupt() }
        sink.flush()
        if (running.isEmpty()) closeSink()
    }

    private fun closeSink() {
        if (sinkClosed.compareAndSet(false, true)) sink.close()
    }

    /** Sets and reports [state]; under the lock, so that the events keep the order of the changes. */
    private fun emit(
        state: ReadingState,
        message: String? = null,
    ) {
        current = state
        events.add(state to message)
        if (eventThread == null) eventThread = newThread(EVENT_THREAD, Runnable { deliverEvents() }).also { it.start() }
    }

    private fun deliverEvents() {
        while (true) {
            val (state, message) = events.take()
            try {
                listener(state, message)
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "Reading listener failed", e)
            }
            if (state in FINAL) return
        }
    }

    private inner class Control : PlayerControl {
        override fun awaitStart(): Boolean =
            core.lock.withLock {
                while (!core.ended && (core.paused || !core.canStart())) core.changed.await()
                if (core.ended) return false
                core.playing = true
                sink.start()
                emit(ReadingState.SPEAKING)
                true
            }

        override fun finished() {
            end(ReadingState.FINISHED, null)
        }

        override fun failed(error: Throwable) = this@ReadingSession.failed(error)

        override fun closeSink() = this@ReadingSession.closeSink()
    }

    internal companion object {
        const val SYNTHESIS_THREAD = "sluide-speech-synth"
        const val PLAYER_THREAD = "sluide-speech-play"
        const val EVENT_THREAD = "sluide-speech-events"
        const val LOOKAHEAD_SECONDS = 120.0
        val POLL_INTERVAL: Duration = Duration.ofMillis(100)
        private val FINAL = setOf(ReadingState.FINISHED, ReadingState.STOPPED, ReadingState.FAILED)

        /** The working list: [startChunk] split for a fast start, then the chunks after it, each with its index. */
        internal fun pieces(
            chunks: List<SpeechChunk>,
            startChunk: Int,
        ): List<Piece> {
            require(startChunk in chunks.indices) { "No chunk $startChunk among ${chunks.size}" }
            return Sentences.splitForFastStart(chunks[startChunk]).map { Piece(it, startChunk) } +
                (startChunk + 1 until chunks.size).map { Piece(chunks[it], it) }
        }

        private fun platformThread(
            name: String,
            task: Runnable,
        ): Thread = Thread(task, name).apply { isDaemon = true }
    }
}
