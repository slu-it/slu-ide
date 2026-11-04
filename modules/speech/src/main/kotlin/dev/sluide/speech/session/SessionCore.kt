package dev.sluide.speech.session

import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.WordTiming
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A piece of a reading, synthesized in one go; [chunkIndex] is the index of the chunk it comes from. */
internal class Piece(
    val chunk: SpeechChunk,
    val chunkIndex: Int,
)

/** The synthesized audio of a piece, kept as 16-bit samples until the player takes it. */
internal class PieceAudio(
    val pcm: ShortArray,
    val sampleRate: Int,
    val words: List<WordTiming>,
)

/**
 * What the synthesis and the player threads of a [ReadingSession] share: one lock with one condition for every
 * change, the flags of the session, the synthesized audio not yet taken by the player, the measurements for
 * [StartRule], and the pieces placed in the sink for [positionAt].
 *
 * Synthesis stops ahead while more than [lookaheadSeconds] are synthesized but not played, unless the reading still
 * waits for the start rule (it would never start). Played frames come from [playedFrames] and change without a signal,
 * so that wait polls every [pollNanos].
 */
internal class SessionCore(
    val pieces: List<Piece>,
    private val lookaheadSeconds: Double,
    private val pollNanos: Long,
    private val playedFrames: () -> Long,
) {
    val lock = ReentrantLock()
    val changed: Condition = lock.newCondition()

    /** Guarded by [lock]: [ReadingSession.start] was called. */
    var started = false

    /** Guarded by [lock]: the user paused. */
    var paused = false

    /** Set under [lock]: the sink was started, the reading speaks or is paused while speaking. */
    @Volatile
    var playing = false

    /** Set under [lock]: the reading finished, stopped or failed; nothing changes afterwards. */
    @Volatile
    var ended = false

    private val audio = arrayOfNulls<PieceAudio>(pieces.size)
    private var synthesized = 0
    private var synthesisDone = false
    private var synthesisNanos = 0L
    private var audioFrames = 0L
    private var sampleRate = 0
    private var synthesizedChars = 0
    private var unsynthesizedChars = pieces.sumOf { it.chunk.text.length }

    /** Written only by the player thread; [placedCount] publishes the entries below it. */
    val placed = arrayOfNulls<PlacedPiece>(pieces.size)

    @Volatile
    var placedCount = 0
        private set

    /** Whether playback may start now; under [lock]. */
    fun canStart(): Boolean =
        StartRule.canStart(
            bufferedSeconds = unplayedSeconds(),
            unsynthesizedChars = unsynthesizedChars,
            synthesizedChars = synthesizedChars,
            synthesisSeconds = synthesisNanos / 1e9,
            audioSeconds = if (sampleRate == 0) 0.0 else audioFrames.toDouble() / sampleRate,
            synthesisDone = synthesisDone,
        )

    /** Waits while the lookahead is full; false once the reading ended. */
    fun awaitRoom(): Boolean =
        lock.withLock {
            while (!ended && unplayedSeconds() > lookaheadSeconds && (playing || canStart())) {
                changed.awaitNanos(pollNanos)
            }
            !ended
        }

    /** Adds the audio of piece [index], synthesized in [nanos]; false once the reading ended. */
    fun add(
        index: Int,
        pieceAudio: PieceAudio,
        nanos: Long,
    ): Boolean =
        lock.withLock {
            if (ended) return false
            check(sampleRate == 0 || sampleRate == pieceAudio.sampleRate) {
                "The sample rate changed from $sampleRate to ${pieceAudio.sampleRate} Hz"
            }
            sampleRate = pieceAudio.sampleRate
            audio[index] = pieceAudio
            synthesized = index + 1
            synthesisNanos += nanos
            audioFrames += pieceAudio.pcm.size
            val chars = pieces[index].chunk.text.length
            synthesizedChars += chars
            unsynthesizedChars -= chars
            changed.signalAll()
            true
        }

    fun synthesisDone() {
        lock.withLock {
            synthesisDone = true
            changed.signalAll()
        }
    }

    /** Waits for the audio of piece [index] and takes it out (it is not kept after playing); null once ended. */
    fun takeAudio(index: Int): PieceAudio? =
        lock.withLock {
            while (!ended && synthesized <= index) changed.await()
            if (ended) null else audio[index].also { audio[index] = null }
        }

    /** The player wrote some audio: synthesis may continue when the lookahead has room again. */
    fun consumed() {
        lock.withLock { changed.signalAll() }
    }

    /** Piece [index] starts at sink frame [startFrame]; player thread only, in order. */
    fun place(
        index: Int,
        startFrame: Long,
        words: List<WordTiming>,
    ) {
        val piece = pieces[index]
        placed[index] =
            PlacedPiece(piece.chunkIndex, piece.chunk.sentenceStart, piece.chunk.sentenceEnd, startFrame, words)
        placedCount = index + 1
    }

    /** Synthesized audio not played yet, in seconds; under [lock]. */
    private fun unplayedSeconds(): Double {
        if (sampleRate == 0) return 0.0
        val played = if (playing) playedFrames() else 0
        return (audioFrames - played).coerceAtLeast(0).toDouble() / sampleRate
    }
}
