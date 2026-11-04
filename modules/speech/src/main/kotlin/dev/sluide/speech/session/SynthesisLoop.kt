package dev.sluide.speech.session

import dev.sluide.speech.audio.Pcm
import dev.sluide.speech.engine.SpeechEngine
import dev.sluide.speech.engine.Voice

/**
 * The synthesis thread of a [ReadingSession]: synthesizes the pieces in order, keeping at most the lookahead ahead of
 * playback, and hands their audio to [core]. Reports any failure to [failed], also an interrupt that does not come
 * from the reading's end.
 */
internal class SynthesisLoop(
    private val core: SessionCore,
    private val engine: SpeechEngine,
    private val voice: Voice,
    private val speed: Double,
    private val nanoTime: () -> Long,
    private val failed: (Throwable) -> Unit,
) : Runnable {
    override fun run() {
        try {
            for (index in core.pieces.indices) {
                if (!core.awaitRoom()) return
                val begin = nanoTime()
                val result = engine.synthesize(core.pieces[index].chunk, voice, speed)
                val nanos = nanoTime() - begin
                val audio = PieceAudio(Pcm.toShorts(result.samples), result.sampleRate, result.words)
                if (!core.add(index, audio, nanos)) return
            }
            core.synthesisDone()
        } catch (e: InterruptedException) {
            if (!core.ended) failed(e)
        } catch (e: Throwable) {
            failed(e)
        }
    }
}
