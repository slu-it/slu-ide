package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TypeScore

/**
 * One sign of a type: a pattern (`^`/`$` match at line breaks) and how much it adds to the type's score. The patterns
 * stay linear on hostile text: no unbounded repetition of groups (Java's regex recurses per repetition) and no two
 * open-ended scans in a row.
 */
internal class Signal(
    pattern: String,
    val weight: Int,
) {
    private val regex = Regex(pattern, RegexOption.MULTILINE)

    fun foundIn(text: String): Boolean = regex.containsMatchIn(text)
}

/** The sum of the weights of the [signals] found in [text], each counted once. */
internal fun weigh(
    text: String,
    signals: List<Signal>,
): Int = signals.sumOf { if (it.foundIn(text)) it.weight else 0 }

/** The highest score a keyword heuristic gives: below the strong signs, a tie with a fenced Markdown text. */
internal const val HEURISTIC_CAP = 90

/** [type] with [weight] capped at [HEURISTIC_CAP], nothing for a weight of 0. */
internal fun heuristic(
    type: TextType,
    weight: Int,
): List<TypeScore> = if (weight > 0) listOf(TypeScore(type, minOf(weight, HEURISTIC_CAP))) else emptyList()
