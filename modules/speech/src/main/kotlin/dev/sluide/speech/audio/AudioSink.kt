package dev.sluide.speech.audio

import dev.sluide.speech.engine.SpeechException

/**
 * Plays mono audio for a reading (ADR 0124). Its calls block, so never call them on the FX thread; [start], [pause],
 * [flush], [close] and [playedFrames] may come from another thread than the one writing.
 */
interface AudioSink : AutoCloseable {
    /** Opens the output for mono samples at [sampleRate] Hz; the sink is paused until [start]. */
    @Throws(SpeechException::class)
    fun open(sampleRate: Int)

    /**
     * Queues [samples] (-1..1, clamped) for playing. Blocks while the output's buffer is full, also while paused;
     * returns early when the sink is flushed or closed meanwhile.
     */
    fun write(samples: FloatArray)

    /** Plays (again) what is buffered and written from now on. */
    fun start()

    /** Stops playing; buffered audio is kept and plays on [start]. */
    fun pause()

    /** Drops buffered audio; [playedFrames] counts from zero again. */
    fun flush()

    /** Frames (samples, mono) played since [open] or the last [flush]; any thread. */
    val playedFrames: Long

    /** Blocks until written audio has played, waiting through a pause; returns early on [flush] or [close]. */
    fun drain()
}
