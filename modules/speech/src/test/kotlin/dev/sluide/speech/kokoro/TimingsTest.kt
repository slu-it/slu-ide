package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.WordTiming
import kotlin.test.Test
import kotlin.test.assertEquals

class TimingsTest {
    // pads of 1 frame, tokens of 2, 2, 4 frames: cumulative 1, 3, 5, 9, 10
    private val durations = floatArrayOf(1f, 2f, 2f, 4f, 1f)

    @Test
    fun `words start and end at the token edges, the pads are skipped`() {
        val words =
            Timings.words(
                listOf(WordSpan(0, 3, 0, 2), WordSpan(4, 7, 2, 3)),
                durations,
                sampleCount = 1000,
                sampleOffset = 0,
            )
        assertEquals(listOf(WordTiming(0, 3, 100, 500), WordTiming(4, 7, 500, 900)), words)
    }

    @Test
    fun `durations are rescaled to the sample count`() {
        val words = Timings.words(listOf(WordSpan(0, 1, 0, 3)), durations, sampleCount = 500, sampleOffset = 0)
        assertEquals(listOf(WordTiming(0, 1, 50, 450)), words)
    }

    @Test
    fun `an offset moves every sample index`() {
        val words = Timings.words(listOf(WordSpan(10, 12, 0, 1)), durations, 1000, 7000)
        assertEquals(listOf(WordTiming(10, 12, 7100, 7300)), words)
    }

    @Test
    fun `a word without tokens has zero length at its edge`() {
        val words = Timings.words(listOf(WordSpan(0, 1, 1, 1)), durations, 1000, 0)
        assertEquals(listOf(WordTiming(0, 1, 300, 300)), words)
    }

    @Test
    fun `no frames or no spans give zero timings or nothing`() {
        assertEquals(
            listOf(WordTiming(0, 1, 5, 5)),
            Timings.words(listOf(WordSpan(0, 1, 0, 1)), FloatArray(3), 0, 5),
        )
        assertEquals(emptyList(), Timings.words(emptyList(), durations, 10, 0))
    }
}
