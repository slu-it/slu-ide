package dev.sluide.speech.session

/**
 * When a reading starts playing (ADR 0124): once the buffered audio is enough to reach the end without a gap, judged
 * by the synthesis so far.
 *
 * With `r` = `synthesisSeconds` / `audioSeconds` (wall time per audio second) and `U` = `unsynthesizedChars` ×
 * `audioSeconds` / `synthesizedChars` (the audio still to synthesize, estimated per char), synthesizing `U` takes
 * `r × U` while the buffer and `U` play, so the gap-free buffer is `(r − 1) × U`; the rule asks 1.2 times that. When
 * synthesis is as fast as playing (`r ≤ 1`), any buffered audio is enough.
 *
 * The buffer asked for is capped at `maxBufferSeconds` ([MAX_BUFFER_SECONDS]), which bounds the memory held before
 * the start: on a machine slower than that, a long reading starts anyway and waits on the gaps.
 */
object StartRule {
    /** The most buffered audio a reading waits for before it starts, and keeps ahead while playing. */
    const val MAX_BUFFER_SECONDS = 300.0

    private const val MARGIN = 1.2

    fun canStart(
        bufferedSeconds: Double,
        unsynthesizedChars: Int,
        synthesizedChars: Int,
        synthesisSeconds: Double,
        audioSeconds: Double,
        synthesisDone: Boolean,
        maxBufferSeconds: Double = MAX_BUFFER_SECONDS,
    ): Boolean {
        if (synthesisDone) return true
        val needed =
            neededSeconds(unsynthesizedChars, synthesizedChars, synthesisSeconds, audioSeconds, maxBufferSeconds)
        return bufferedSeconds > 0 && bufferedSeconds >= needed
    }

    /**
     * The buffered audio the reading needs to reach the end without a gap, at most [maxBufferSeconds]: 0 when synthesis
     * keeps up, [maxBufferSeconds] while nothing is synthesized (no rate to judge by).
     */
    fun neededSeconds(
        unsynthesizedChars: Int,
        synthesizedChars: Int,
        synthesisSeconds: Double,
        audioSeconds: Double,
        maxBufferSeconds: Double = MAX_BUFFER_SECONDS,
    ): Double {
        if (audioSeconds <= 0 || synthesizedChars <= 0) return maxBufferSeconds
        val rate = synthesisSeconds / audioSeconds
        if (rate <= 1) return 0.0
        val unsynthesizedSeconds = unsynthesizedChars * audioSeconds / synthesizedChars
        return (MARGIN * (rate - 1) * unsynthesizedSeconds).coerceAtMost(maxBufferSeconds)
    }
}
