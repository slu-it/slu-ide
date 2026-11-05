package dev.sluide.translation.opusmt

/**
 * Splits a sentence that is too long for a model (512 input tokens with `</s>`) into parts that fit (ADR 0126): at the
 * clause boundary (`, ; :` and their CJK forms) nearest the middle, else at the space nearest the middle, else at the
 * character middle, again for each part that is still too long.
 */
internal object LongSentences {
    /** Input tokens per part, with `</s>`; below the models' 512 to leave room. */
    const val LIMIT = 400

    private val CLAUSE_MARKS = setOf(',', ';', ':', '，', '；', '：', '、')

    /** Languages written without spaces between words: their parts are joined without one. */
    private val UNSPACED = setOf("zh", "ja")

    /** [sentence] in parts of at most [limit] tokens each by [tokens] (which counts `</s>`), in order. */
    fun parts(
        sentence: String,
        tokens: (String) -> Int,
        limit: Int = LIMIT,
    ): List<String> {
        if (sentence.trim().length < 2 || tokens(sentence) <= limit) return listOf(sentence)
        val at = splitPoint(sentence)
        val left = sentence.substring(0, at).trim()
        val right = sentence.substring(at).trim()
        if (left.isEmpty() || right.isEmpty()) return listOf(sentence)
        return parts(left, tokens, limit) + parts(right, tokens, limit)
    }

    /** The translated [parts] as one text in [language]. */
    fun join(
        parts: List<String>,
        language: String,
    ): String = parts.filter { it.isNotBlank() }.joinToString(if (language in UNSPACED) "" else " ")

    /**
     * Where [text] is cut, so that both sides keep a non-blank character: after the clause mark nearest the middle, at
     * the space nearest it, or in the middle of the non-blank stretch (never inside a surrogate pair).
     */
    internal fun splitPoint(text: String): Int {
        val first = text.indexOfFirst { !it.isWhitespace() }
        val last = text.indexOfLast { !it.isWhitespace() }
        val middle = text.length / 2
        nearest(text, middle) { i -> text[i] in CLAUSE_MARKS && i < last }?.let { return it + 1 }
        nearest(text, middle) { i -> text[i].isWhitespace() && i in first + 1..last }?.let { return it }
        val cut = middle.coerceIn(first + 1, last)
        return if (Character.isLowSurrogate(text[cut]) && cut + 1 <= last) cut + 1 else cut
    }

    private fun nearest(
        text: String,
        middle: Int,
        matches: (Int) -> Boolean,
    ): Int? = text.indices.filter(matches).minByOrNull { kotlin.math.abs(it - middle) }
}
