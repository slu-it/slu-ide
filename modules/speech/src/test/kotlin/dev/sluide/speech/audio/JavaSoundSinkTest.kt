package dev.sluide.speech.audio

import dev.sluide.speech.engine.SpeechException
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSoundSinkTest {
    @Test
    @Timeout(60)
    fun `plays silence to its end on the default output`() {
        // Opening a line needs an audio device: the test tasks point ALSA at a null device where there is no sound
        // card (docs/testing.md, "Media playback"); where even that is missing, this is skipped.
        val sink = JavaSoundSink()
        try {
            sink.open(RATE)
        } catch (e: SpeechException) {
            abort<Unit>("no audio output here: ${e.message}")
        }
        sink.use {
            assertEquals(0, it.playedFrames)

            it.start()
            it.write(FloatArray(RATE / 5))
            it.drain()

            assertTrue(it.playedFrames <= RATE / 5, "played ${it.playedFrames} frames of ${RATE / 5}")
        }
        assertEquals(0, sink.playedFrames)
    }

    @Test
    fun `writes and drains after close return at once`() {
        val sink = JavaSoundSink()
        sink.close()

        sink.write(FloatArray(RATE))
        sink.drain()
        sink.flush()

        assertEquals(0, sink.playedFrames)
    }

    private companion object {
        const val RATE = 24_000
    }
}
