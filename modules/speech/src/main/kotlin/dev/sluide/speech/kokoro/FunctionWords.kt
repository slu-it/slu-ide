package dev.sluide.speech.kokoro

/**
 * Words espeak speaks on their own as if stressed ("a" as the letter A). Said in a sentence they are weak: the
 * tokenizer gives "a" the schwa `ɐ` and takes the stress marks off the others.
 */
internal object FunctionWords {
    private val UNSTRESSED =
        setOf(
            "an",
            "the",
            "of",
            "to",
            "and",
            "or",
            "but",
            "in",
            "on",
            "at",
            "for",
            "from",
            "by",
            "with",
            "as",
            "is",
            "are",
            "was",
            "were",
            "be",
            "it",
            "its",
            "that",
            "than",
            "then",
        )

    /**
     * "a", and "A" when it starts the chunk or follows punctuation and a word follows it; elsewhere it is a letter
     * ("Grade A students", "plan A or B", "vitamin A.").
     */
    fun isArticleA(
        word: String,
        previousIsWord: Boolean,
        nextIsWord: Boolean,
    ): Boolean = word == "a" || (word == "A" && !previousIsWord && nextIsWord)

    /** [word] in lower case or with a capital first letter; shouted "IT" or "AS" stay as they are. */
    fun isUnstressed(word: String): Boolean {
        val lower = word.lowercase()
        return lower in UNSTRESSED && (word == lower || word == lower.replaceFirstChar { it.uppercaseChar() })
    }
}
