package dev.sluide.translation.opusmt

import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** One decoder step of a [BeamSearch], with the beams as the batch; [MarianSession] runs the model behind it. */
internal interface BeamStep {
    /** The scores (logits or log-probs) of the next token, one row per beam, after each beam's [lastTokens]. */
    fun next(lastTokens: IntArray): Array<FloatArray>

    /** The next step continues beam `i` from the former beam `sources[i]`: reorder the cache to match. */
    fun reorder(sources: IntArray)
}

/**
 * Beam search with Hugging Face's semantics (`early_stopping` false): [numBeams] running beams, the best `2 *
 * numBeams` candidates per step, an [eosId] among the best [numBeams] ends a hypothesis scored by its log-prob sum over
 * its length (with `</s>`, start token excluded) to the power [lengthPenalty]. The search stops when [numBeams]
 * hypotheses are finished and no running beam can beat the worst of them, or when the sequence reaches [maxLength]
 * (counting the start token); then the running beams count as hypotheses too.
 *
 * Each step's scores are log-softmaxed, [badWordIds] masked, all but [forcedEosId] masked at the last position, and
 * log-softmaxed again when [renormalize] is set (`renormalize_logits`).
 */
internal class BeamSearch(
    private val numBeams: Int,
    private val eosId: Int,
    private val maxLength: Int,
    private val badWordIds: Set<Int> = emptySet(),
    private val forcedEosId: Int? = null,
    private val lengthPenalty: Double = 1.0,
    private val renormalize: Boolean = false,
) {
    constructor(config: MarianConfig, maxLength: Int = config.maxLength) : this(
        config.numBeams,
        config.eosId,
        maxLength,
        config.badWordIds,
        config.forcedEosId,
        config.lengthPenalty,
        config.renormalize,
    )

    /**
     * The tokens of the best hypothesis after [startId] (without it; with `</s>` when it ended there). Checks
     * [cancelled] before every step and throws [CancellationException] when it is true.
     */
    fun search(
        startId: Int,
        step: BeamStep,
        cancelled: () -> Boolean,
    ): IntArray {
        val beams = numBeams
        var tokens = Array(beams) { IntArray(0) }
        var scores = DoubleArray(beams) { if (it == 0) 0.0 else INITIAL_OTHER_BEAMS }
        var last = IntArray(beams) { startId }
        val finished = Hypotheses(beams, lengthPenalty)
        var length = 1
        var done = false
        while (length < maxLength) {
            if (cancelled()) throw CancellationException()
            val rows = step.next(last)
            require(rows.size == beams) { "The step returned ${rows.size} rows for $beams beams" }
            val candidates = Candidates(2 * beams)
            for (beam in 0 until beams) {
                val logProbs = process(rows[beam], length)
                for (token in logProbs.indices) candidates.offer(logProbs[token] + scores[beam], beam, token)
            }
            val nextTokens = Array(beams) { IntArray(0) }
            val nextScores = DoubleArray(beams)
            val nextLast = IntArray(beams)
            val sources = IntArray(beams)
            var running = 0
            for (rank in 0 until candidates.size) {
                val beam = candidates.beam[rank]
                val token = candidates.token[rank]
                if (token == eosId) {
                    if (rank < beams) finished.add(tokens[beam] + token, candidates.score[rank])
                    continue
                }
                nextTokens[running] = tokens[beam] + token
                nextScores[running] = candidates.score[rank]
                nextLast[running] = token
                sources[running] = beam
                running++
                if (running == beams) break
            }
            length++
            if (finished.done(candidates.score[0], length - 1)) {
                done = true
                break
            }
            tokens = nextTokens
            scores = nextScores
            last = nextLast
            if (length < maxLength) step.reorder(sources)
        }
        if (!done) {
            for (beam in 0 until beams) finished.add(tokens[beam], scores[beam])
        }
        return finished.best()
    }

    /** The log-probs of the next token from a step's [row] at sequence [length], with the logits processors applied. */
    private fun process(
        row: FloatArray,
        length: Int,
    ): DoubleArray {
        val logProbs = logSoftmax(DoubleArray(row.size) { row[it].toDouble() })
        for (id in badWordIds) if (id in logProbs.indices) logProbs[id] = Double.NEGATIVE_INFINITY
        val forced = forcedEosId
        if (forced != null && length == maxLength - 1 && forced in logProbs.indices) {
            logProbs.fill(Double.NEGATIVE_INFINITY)
            logProbs[forced] = 0.0
        }
        return if (renormalize) logSoftmax(logProbs) else logProbs
    }

    /** The best `capacity` candidates (score, beam, token) seen, in descending score order. */
    private class Candidates(
        private val capacity: Int,
    ) {
        val score = DoubleArray(capacity)
        val beam = IntArray(capacity)
        val token = IntArray(capacity)
        var size = 0
            private set

        fun offer(
            value: Double,
            fromBeam: Int,
            id: Int,
        ) {
            if (size == capacity && !(value > score[size - 1])) return
            var at = if (size < capacity) size++ else size - 1
            while (at > 0 && value > score[at - 1]) {
                score[at] = score[at - 1]
                beam[at] = beam[at - 1]
                token[at] = token[at - 1]
                at--
            }
            score[at] = value
            beam[at] = fromBeam
            token[at] = id
        }
    }

    /** The finished hypotheses, at most `capacity` of them (Hugging Face's `BeamHypotheses`). */
    private class Hypotheses(
        private val capacity: Int,
        private val lengthPenalty: Double,
    ) {
        private val entries = ArrayList<Pair<Double, IntArray>>()
        private var worst = Double.MAX_VALUE

        fun add(
            tokens: IntArray,
            sumLogProbs: Double,
        ) {
            val score =
                sumLogProbs /
                    tokens.size
                        .coerceAtLeast(1)
                        .toDouble()
                        .pow(lengthPenalty)
            if (entries.size < capacity || score > worst) {
                entries.add(score to tokens)
                if (entries.size > capacity) {
                    entries.remove(entries.minBy { it.first })
                    worst = entries.minOf { it.first }
                } else {
                    worst = minOf(score, worst)
                }
            }
        }

        /** Whether no running beam can beat the hypotheses: [bestSum] spread over [generated] tokens. */
        fun done(
            bestSum: Double,
            generated: Int,
        ): Boolean = entries.size >= capacity && worst >= bestSum / generated.toDouble().pow(lengthPenalty)

        fun best(): IntArray = entries.maxBy { it.first }.second
    }

    private companion object {
        /** Hugging Face's start score of all but the first beam, so that the first step expands only one. */
        const val INITIAL_OTHER_BEAMS = -1e9

        fun logSoftmax(values: DoubleArray): DoubleArray {
            val max = values.max()
            if (max == Double.NEGATIVE_INFINITY) return values
            var sum = 0.0
            for (v in values) sum += exp(v - max)
            val log = max + ln(sum)
            for (i in values.indices) values[i] -= log
            return values
        }
    }
}

/**
 * A cache tensor with the beams as its first dimension (`[B, ...]`, flat), reordered so that beam `i` holds what beam
 * `sources[i]` held; B is `sources.size`.
 */
internal fun reorderBeams(
    cache: FloatArray,
    sources: IntArray,
): FloatArray {
    val block = cache.size / sources.size
    val reordered = FloatArray(cache.size)
    for ((beam, source) in sources.withIndex()) {
        cache.copyInto(
            reordered,
            beam * block,
            source * block,
            (source + 1) * block,
        )
    }
    return reordered
}

/** The rows of the last position per beam from flat logits of shape `[beams, positions, vocab]`. */
internal fun lastPositions(
    logits: FloatArray,
    beams: Int,
    positions: Int,
    vocab: Int,
): Array<FloatArray> =
    Array(beams) { beam ->
        val start = (beam * positions + positions - 1) * vocab
        logits.copyOfRange(start, start + vocab)
    }
