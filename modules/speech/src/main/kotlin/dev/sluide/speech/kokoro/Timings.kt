package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.WordTiming
import kotlin.math.roundToInt

/** Turns the model's per-token durations into the sample range of each word. */
internal object Timings {
    /**
     * [durations] has one entry per token including both pad tokens, in frames; their running sum is rescaled to
     * [sampleCount] samples (the frame length does not exactly match the waveform). A token at index `t` (without
     * pads) starts at the sum of the durations before it, which is the edge `t + 1`. [sampleOffset] is added to every
     * sample index (the piece starts that far into the chunk's audio).
     */
    fun words(
        spans: List<WordSpan>,
        durations: FloatArray,
        sampleCount: Int,
        sampleOffset: Int,
    ): List<WordTiming> {
        val edges = IntArray(durations.size + 1)
        val total = durations.sum()
        var cumulative = 0.0
        for (i in durations.indices) {
            cumulative += durations[i]
            edges[i + 1] = if (total > 0f) (cumulative * sampleCount / total).roundToInt() else 0
        }

        fun edge(index: Int) = sampleOffset + edges[index.coerceIn(0, edges.size - 1)]
        return spans.map { span ->
            val start = edge(span.firstToken + 1)
            val end = if (span.endToken <= span.firstToken) start else edge(span.endToken + 1)
            WordTiming(span.start, span.end, start, maxOf(start, end))
        }
    }
}
