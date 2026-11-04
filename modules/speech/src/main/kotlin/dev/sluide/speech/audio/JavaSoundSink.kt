package dev.sluide.speech.audio

import dev.sluide.speech.engine.SpeechException
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.withLock

/**
 * An [AudioSink] on the default javax.sound output: 16-bit signed little-endian mono PCM through a [SourceDataLine]
 * with a buffer of about [BUFFER_SECONDS], so pause and stop take effect quickly. Thread-safe; [open], [write] and
 * [drain] block outside the lock, so [pause], [flush] and [close] from another thread neither wait for them nor miss
 * waking them.
 *
 * [openLine] opens a line for a format with a buffer of the given bytes; tests replace it with a fake line.
 */
class JavaSoundSink internal constructor(
    private val openLine: (format: AudioFormat, bufferBytes: Int) -> SourceDataLine,
) : AudioSink {
    constructor() : this(::openDefaultLine)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var running = false

    @Volatile
    private var closed = false

    /** [open] is opening a line, outside the lock. */
    private var opening = false

    /** Counts pauses, so that a drain cut short by a pause drains again even when started again meanwhile. */
    private var pauses = 0

    /** Counts flushes, so that a write or drain started before one returns. */
    private var flushes = 0

    /** The line's frame position at the last flush; [playedFrames] counts from it. */
    @Volatile
    private var baseFrame = 0L

    /** Frames written since open or the last flush; [playedFrames] never exceeds it. */
    @Volatile
    private var writtenFrames = 0L

    override fun open(sampleRate: Int) {
        lock.withLock {
            check(line == null && !opening && !closed) { "The sink is open or closed already" }
            opening = true
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val opened =
            try {
                openLine(format, (sampleRate * BUFFER_SECONDS).toInt() * format.frameSize)
            } catch (e: Exception) {
                lock.withLock { opening = false }
                throw when (e) {
                    is LineUnavailableException, is IllegalArgumentException, is SecurityException -> noOutput(e)
                    else -> e
                }
            }
        val kept =
            lock.withLock {
                opening = false
                if (!closed) {
                    line = opened
                    if (running) opened.start()
                }
                !closed
            }
        // Closed while opening: the line was never ours to use.
        if (!kept) opened.close()
    }

    override fun write(samples: FloatArray) {
        val bytes = Pcm.toLittleEndianBytes(samples)
        val flushesAtStart = lock.withLock { flushes }
        var offset = 0
        while (offset < bytes.size) {
            val (target, length) =
                lock.withLock {
                    val current = line
                    if (current == null || closed || flushes != flushesAtStart) return
                    // A stopped line takes only what fits into its buffer; then wait for start, flush or close.
                    val length =
                        if (running) {
                            bytes.size - offset
                        } else {
                            current.available().coerceAtMost(bytes.size - offset)
                        }
                    if (length < FRAME_BYTES && !awaitChange()) return
                    current to length - length % FRAME_BYTES
                }
            if (length == 0) continue
            val written = target.write(bytes, offset, length)
            offset += written
            lock.withLock { if (flushes == flushesAtStart) writtenFrames += written / FRAME_BYTES }
        }
    }

    override fun start() {
        lock.withLock {
            running = true
            line?.start()
            changed.signalAll()
        }
    }

    override fun pause() {
        lock.withLock {
            running = false
            pauses++
            line?.stop()
            changed.signalAll()
        }
    }

    override fun flush() {
        lock.withLock {
            flushes++
            line?.let {
                it.flush()
                baseFrame = it.longFramePosition
            }
            writtenFrames = 0
            changed.signalAll()
        }
    }

    override val playedFrames: Long
        get() {
            val current = line ?: return 0
            return (current.longFramePosition - baseFrame).coerceIn(0, writtenFrames)
        }

    override fun drain() {
        val flushesAtStart = lock.withLock { flushes }
        while (true) {
            var pausesBefore = 0
            val target =
                lock.withLock {
                    val current = line
                    if (current == null || closed || flushes != flushesAtStart) return
                    if (!running) {
                        if (!awaitChange()) return
                        null
                    } else {
                        pausesBefore = pauses
                        current
                    }
                } ?: continue
            target.drain()
            // A pause stops the line and ends its drain early: wait for the start and drain the rest.
            lock.withLock { if (closed || flushes != flushesAtStart || (running && pauses == pausesBefore)) return }
        }
    }

    override fun close() {
        lock.withLock {
            closed = true
            running = false
            line?.close()
            line = null
            changed.signalAll()
        }
    }

    /** Waits for start, pause, flush or close; false when interrupted (the interrupt flag is kept). */
    private fun awaitChange(): Boolean =
        try {
            changed.await()
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun noOutput(cause: Exception) =
        SpeechException("No audio output: ${cause.message ?: cause.javaClass.simpleName}", cause)

    private companion object {
        const val BUFFER_SECONDS = 0.3
        const val FRAME_BYTES = 2

        fun openDefaultLine(
            format: AudioFormat,
            bufferBytes: Int,
        ): SourceDataLine = AudioSystem.getSourceDataLine(format).apply { open(format, bufferBytes) }
    }
}
