package dev.sluide.translation.opusmt

import java.util.concurrent.CancellationException
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val EOS = 0
private const val A = 1
private const val B = 2
private const val C = 3
private const val PAD = 4
private const val START = PAD
private const val VOCAB = 5

/**
 * A fake model: the probabilities of the next token are a function of a beam's tokens so far. It keeps the beams'
 * histories the way the real decoder keeps its cache, through [reorder], so a wrong reorder shows as a wrong path.
 */
private class FakeStep(
    private val probabilities: (List<Int>) -> Map<Int, Double>,
) : BeamStep {
    private var histories: List<List<Int>> = emptyList()
    var steps = 0

    override fun next(lastTokens: IntArray): Array<FloatArray> {
        steps++
        if (histories.isEmpty()) histories = lastTokens.map { emptyList() }
        histories = histories.mapIndexed { beam, history -> history + lastTokens[beam] }
        return Array(lastTokens.size) { beam ->
            val known = probabilities(histories[beam].drop(1))
            FloatArray(VOCAB) { token -> ln(known[token] ?: 1e-6).toFloat() }
        }
    }

    override fun reorder(sources: IntArray) {
        histories = sources.map { histories[it] }
    }
}

class BeamSearchTest {
    /**
     * Greedy picks A first (0.5 against 0.4) and ends with A C `</s>` (0.2 over three tokens); B `</s>` (0.36 over
     * two) is better per token.
     */
    private val greedyTrap: (List<Int>) -> Map<Int, Double> = { history ->
        when (history) {
            emptyList<Int>() -> mapOf(A to 0.5, B to 0.4, EOS to 0.1)
            listOf(A) -> mapOf(C to 0.4, EOS to 0.3, B to 0.3)
            listOf(B) -> mapOf(EOS to 0.9, C to 0.1)
            else -> mapOf(EOS to 1.0)
        }
    }

    private fun search(
        step: BeamStep,
        beams: Int = 2,
        maxLength: Int = 10,
        badWords: Set<Int> = emptySet(),
        forcedEos: Int? = null,
        lengthPenalty: Double = 1.0,
        cancelled: () -> Boolean = { false },
    ): List<Int> =
        BeamSearch(beams, EOS, maxLength, badWords, forcedEos, lengthPenalty, renormalize = true)
            .search(START, step, cancelled)
            .toList()

    @Test
    fun `finds the best hypothesis that greedy decoding misses`() {
        assertEquals(listOf(B, EOS), search(FakeStep(greedyTrap)))
    }

    @Test
    fun `one beam decodes greedily`() {
        assertEquals(listOf(A, C, EOS), search(FakeStep(greedyTrap), beams = 1))
    }

    @Test
    fun `a higher length penalty favours the longer hypothesis`() {
        assertEquals(listOf(A, C, EOS), search(FakeStep(greedyTrap), lengthPenalty = 2.0))
    }

    @Test
    fun `the end token ends a hypothesis and the search stops when no running beam can win`() {
        val step = FakeStep { mapOf(EOS to 0.9, A to 0.05, B to 0.05) }
        assertEquals(listOf(EOS), search(step))
        // Two steps finish two hypotheses, and no running beam can beat them any more.
        assertTrue(step.steps <= 2, "steps: ${step.steps}")
    }

    @Test
    fun `a bad word is never chosen even when the model likes it best`() {
        val step = FakeStep { history -> if (history.size < 3) mapOf(PAD to 0.9, A to 0.1) else mapOf(EOS to 1.0) }
        val tokens = search(step, badWords = setOf(PAD))
        assertTrue(PAD !in tokens, "tokens: $tokens")
        assertEquals(listOf(A, A, A, EOS), tokens)
    }

    @Test
    fun `the end token is forced at the last position`() {
        val never = FakeStep { mapOf(A to 0.7, B to 0.3) }
        assertEquals(listOf(A, A, A, EOS), search(never, maxLength = 5, forcedEos = EOS))
    }

    @Test
    fun `without a forced end the search stops at the maximum length`() {
        val never = FakeStep { mapOf(A to 0.7, B to 0.3) }
        assertEquals(listOf(A, A, A, A), search(never, maxLength = 5))
    }

    @Test
    fun `cancelling stops before the next decoder step`() {
        val step = FakeStep { mapOf(A to 1.0) }
        assertFailsWith<CancellationException> { search(step, cancelled = { step.steps >= 2 }) }
        assertEquals(2, step.steps)
    }

    @Test
    fun `the beams' caches are reordered with the chosen beams`() {
        // B A </s> wins only when the third step sees the history B A, which the reorder moves to beam 0.
        // (Three tokens at most, so that a long run of likely C tokens cannot win by its length.)
        val step =
            FakeStep { history ->
                when (history) {
                    emptyList<Int>() -> mapOf(A to 0.55, B to 0.45)
                    listOf(A) -> mapOf(C to 0.5, B to 0.5)
                    listOf(B) -> mapOf(A to 0.99)
                    listOf(B, A) -> mapOf(EOS to 0.99)
                    else -> mapOf(EOS to 0.01, C to 0.99)
                }
            }
        assertContentEquals(listOf(B, A, EOS), search(step, maxLength = 4))
    }

    @Test
    fun `a cache tensor is reordered by copying each beam's block from its source beam`() {
        // [B=3, heads=1, T=2, headDim=2]: beam b holds 10b, 10b+1, 10b+2, 10b+3.
        val cache = FloatArray(12) { (it / 4 * 10 + it % 4).toFloat() }
        val reordered = reorderBeams(cache, intArrayOf(2, 0, 0))
        assertContentEquals(
            floatArrayOf(20f, 21f, 22f, 23f, 0f, 1f, 2f, 3f, 0f, 1f, 2f, 3f),
            reordered,
        )
        assertContentEquals(FloatArray(12) { (it / 4 * 10 + it % 4).toFloat() }, cache, "the input stays as it was")
    }

    @Test
    fun `the logits of each beam's last position`() {
        // [B=2, T=3, V=2]: the logit of beam b, position t, token v is 100b + 10t + v.
        val logits = FloatArray(12) { (it / 6 * 100 + it % 6 / 2 * 10 + it % 2).toFloat() }
        val rows = lastPositions(logits, beams = 2, positions = 3, vocab = 2)
        assertContentEquals(floatArrayOf(20f, 21f), rows[0])
        assertContentEquals(floatArrayOf(120f, 121f), rows[1])
    }
}
