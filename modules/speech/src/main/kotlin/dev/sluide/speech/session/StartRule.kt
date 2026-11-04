package dev.sluide.speech.session

/**
 * When a reading starts playing (ADR 0124): once the buffered audio is enough to reach the end without a gap, judged
 * by the synthesis so far.
 *
 * With `r` = [synthesisSeconds] / [audioSeconds] (wall time per audio second) and `U` = [unsynthesizedChars] ×
 * [audioSeconds] / [synthesizedChars] (the audio still to synthesize, estimated per char), synthesizing `U` takes
 * `r × U` while `bufferedSeconds + U` play, so the gap-free buffer is `(r − 1) × U`; the rule asks 1.2 times that.
 * When synthesis is as fast as playing (`r ≤ 1`), any buffered audio is enough.
 */
object StartRule {
    private const val MARGIN = 1.2

    fun canStart(
        bufferedSeconds: Double,
        unsynthesizedChars: Int,
        synthesizedChars: Int,
        synthesisSeconds: Double,
        audioSeconds: Double,
        synthesisDone: Boolean,
    ): Boolean {
        if (synthesisDone) return true
        if (bufferedSeconds <= 0 || audioSeconds <= 0 || synthesizedChars <= 0) return false
        val rate = synthesisSeconds / audioSeconds
        if (rate <= 1) return true
        val unsynthesizedSeconds = unsynthesizedChars * audioSeconds / synthesizedChars
        return bufferedSeconds >= MARGIN * (rate - 1) * unsynthesizedSeconds
    }
}
