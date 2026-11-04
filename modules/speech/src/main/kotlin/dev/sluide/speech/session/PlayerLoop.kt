package dev.sluide.speech.session

import dev.sluide.speech.audio.AudioSink
import dev.sluide.speech.audio.Pcm

/** What the [PlayerLoop] needs from its [ReadingSession]. */
internal interface PlayerControl {
    /** Waits until the reading may start, then starts the sink and reports speaking; false once the reading ended. */
    fun awaitStart(): Boolean

    /** All audio has played. */
    fun finished()

    fun failed(error: Throwable)

    /** Closes the sink, once. */
    fun closeSink()
}

/**
 * The player thread of a [ReadingSession]: opens [sink] at the first piece's sample rate, waits for the start, then
 * writes the pieces in order in slices of [SLICE_SECONDS], placing each at the frame where it starts; waits for the
 * next piece on an underrun; drains after the last. Closes the sink in any case.
 */
internal class PlayerLoop(
    private val core: SessionCore,
    private val sink: AudioSink,
    private val control: PlayerControl,
) : Runnable {
    override fun run() {
        try {
            play()
        } catch (e: InterruptedException) {
            if (!core.ended) control.failed(e)
        } catch (e: Throwable) {
            control.failed(e)
        } finally {
            // The end interrupts this thread too; closing the sink is no wait to cut short.
            Thread.interrupted()
            control.closeSink()
        }
    }

    private fun play() {
        var audio = core.takeAudio(0) ?: return
        sink.open(audio.sampleRate)
        if (!control.awaitStart()) return
        val slice = (audio.sampleRate * SLICE_SECONDS).toInt().coerceAtLeast(1)
        var frame = 0L
        for (index in core.pieces.indices) {
            if (index > 0) audio = core.takeAudio(index) ?: return
            core.place(index, frame, audio.words)
            var from = 0
            while (from < audio.pcm.size) {
                if (core.ended) return
                val to = minOf(from + slice, audio.pcm.size)
                sink.write(Pcm.toFloats(audio.pcm, from, to))
                from = to
                core.consumed()
            }
            frame += audio.pcm.size
        }
        if (core.ended) return
        sink.drain()
        if (core.ended) return
        control.closeSink()
        control.finished()
    }

    private companion object {
        const val SLICE_SECONDS = 0.1
    }
}
