package dev.sluide.texttypes

import dev.sluide.texttypes.rules.BuiltInRules

/** How sure a [TextTypeSource] is that a text has [type]: 1..100, higher is surer. */
data class TypeScore(
    val type: TextType,
    val score: Int,
)

/** Scores the types a text may have; an empty list when it sees none. May throw: the detector counts that as none. */
fun interface TextTypeSource {
    fun scores(text: String): List<TypeScore>
}

/**
 * Guesses the type of a text (ADR 0187): the built-in rules and the given [sources] score types, the highest score
 * wins, ties go to the type earlier in [TextTypes.all], and a best score below [MIN_SCORE] means Plain Text.
 * Thread-safe, never throws; meant to run off the FX thread.
 */
class TextTypeDetector internal constructor(
    sources: List<TextTypeSource>,
    rules: List<TextTypeSource>,
) {
    constructor(sources: List<TextTypeSource> = emptyList()) : this(sources, BuiltInRules.all)

    private val sources = sources.toList()
    private val rules = rules.toList()

    fun detect(text: String): TextType {
        val content = text.removePrefix(BOM)
        if (content.isBlank()) return TextTypes.PLAIN_TEXT
        val ruleText = content.take(RULES_LIMIT)
        val asked = if (content.length <= SOURCE_LIMIT) sources else emptyList()
        val scores = rules.flatMap { scoresOf(it, ruleText) } + asked.flatMap { scoresOf(it, content) }
        val best =
            scores.maxWithOrNull(compareBy<TypeScore> { it.score }.thenByDescending { order.getValue(it.type) })
                ?: return TextTypes.PLAIN_TEXT
        return if (best.score >= MIN_SCORE) best.type else TextTypes.PLAIN_TEXT
    }

    /**
     * The scores [source] gives, none if it throws (also an [Error]). A score above 100 breaks the contract and is dropped, not clamped;
     * one below 1 cannot reach [MIN_SCORE] anyway. No extension and types outside [TextTypes.all] are never the answer.
     */
    private fun scoresOf(
        source: TextTypeSource,
        text: String,
    ): List<TypeScore> =
        try {
            source.scores(text).filter { it.score <= 100 && it.type in order }
        } catch (_: Throwable) {
            // Also an Error (a stack overflow on deeply nested text): the paste that asks must not end on its thread.
            emptyList()
        }

    companion object {
        private const val BOM = "\uFEFF"

        /** Each type's place in [TextTypes.all] for ties; without No extension, which is never detected. */
        private val order: Map<TextType, Int> =
            (TextTypes.all - TextTypes.NO_EXTENSION).withIndex().associate { (index, type) -> type to index }

        /** The lowest best score that names a type; below it the text is Plain Text. */
        const val MIN_SCORE = 50

        /** The longest text (in chars, without a BOM) the [TextTypeSource]s given to the detector are asked about. */
        const val SOURCE_LIMIT = 1 shl 20

        /** How many chars from the start the built-in rules look at. */
        const val RULES_LIMIT = 64 * 1024
    }
}
