package dev.sluide.speech.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine

/**
 * A line that plays only when the test calls [play]. Like the JDK's lines, [write] blocks while the buffer is full and
 * the line runs, and returns early once it is stopped, flushed or closed; [drain] returns when the buffer is empty or
 * the line stops. [onDrain] runs once at the start of the next drain, then that drain returns as if cut short.
 */
internal class FakeLine(
    private val format: AudioFormat,
    private val capacity: Int,
) : SourceDataLine {
    private val monitor = Object()
    private var buffered = 0
    private var position = 0L
    private var active = false
    private var open = true
    private var flushes = 0

    @Volatile
    var onDrain: (() -> Unit)? = null

    /** Calls of [write] and [drain] that are blocked in the line right now. */
    @Volatile
    var blocked = 0
        private set

    /** Plays up to [frames] buffered frames while the line runs. */
    fun play(frames: Int) =
        synchronized(monitor) {
            val played = if (active) minOf(frames, buffered / format.frameSize) else 0
            buffered -= played * format.frameSize
            position += played
            monitor.notifyAll()
            played
        }

    val bufferedBytes: Int get() = synchronized(monitor) { buffered }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int =
        synchronized(monitor) {
            val flushesAtStart = flushes
            var written = 0
            while (open && flushes == flushesAtStart) {
                val chunk = minOf(capacity - buffered, len - written)
                buffered += chunk
                written += chunk
                if (written == len || !active) break
                blocked++
                monitor.wait()
                blocked--
            }
            written
        }

    override fun drain() {
        onDrain?.let {
            onDrain = null
            it()
            return
        }
        synchronized(monitor) {
            while (buffered > 0 && active && open) {
                blocked++
                monitor.wait()
                blocked--
            }
        }
    }

    override fun flush() =
        synchronized(monitor) {
            buffered = 0
            flushes++
            monitor.notifyAll()
        }

    override fun start() =
        synchronized(monitor) {
            active = true
            monitor.notifyAll()
        }

    override fun stop() =
        synchronized(monitor) {
            active = false
            monitor.notifyAll()
        }

    override fun close() =
        synchronized(monitor) {
            open = false
            monitor.notifyAll()
        }

    override fun isOpen() = synchronized(monitor) { open }

    override fun isRunning() = synchronized(monitor) { active }

    override fun isActive() = isRunning

    override fun available() = synchronized(monitor) { capacity - buffered }

    override fun getLongFramePosition() = synchronized(monitor) { position }

    override fun getFramePosition() = longFramePosition.toInt()

    override fun getMicrosecondPosition() = (longFramePosition * 1_000_000 / format.frameRate).toLong()

    override fun getFormat() = format

    override fun getBufferSize() = capacity

    override fun getLevel() = AudioSystem.NOT_SPECIFIED.toFloat()

    override fun open(
        format: AudioFormat,
        bufferSize: Int,
    ) = Unit

    override fun open(format: AudioFormat) = Unit

    override fun open() = Unit

    override fun getLineInfo(): Line.Info = DataLine.Info(SourceDataLine::class.java, format)

    override fun getControls(): Array<Control> = emptyArray()

    override fun isControlSupported(control: Control.Type) = false

    override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("no controls")

    override fun addLineListener(listener: LineListener) = Unit

    override fun removeLineListener(listener: LineListener) = Unit
}
