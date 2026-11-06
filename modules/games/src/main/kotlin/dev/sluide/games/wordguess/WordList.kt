package dev.sluide.games.wordguess

import java.util.concurrent.ConcurrentHashMap

/**
 * The words of one [Language]: the [answers] a round may pick from and the larger set of words a guess may be (the
 * allowed list contains every answer). Words are upper case; the lists live next to this class (ADR 0132).
 */
internal class WordList(
    val answers: List<String>,
    private val allowed: Set<String>,
) {
    /** Whether [word], in upper case, may be guessed. */
    fun isAllowed(word: String): Boolean = word in allowed

    companion object {
        private val cache = ConcurrentHashMap<Language, WordList>()

        /** The lists of [language], read once and then kept. A missing resource is a programming error. */
        fun load(language: Language): WordList =
            cache.computeIfAbsent(language) {
                val answers = read("${it.code}-answers.txt")
                WordList(answers, read("${it.code}-allowed.txt").toHashSet())
            }

        private fun read(name: String): List<String> {
            val stream =
                WordList::class.java.getResourceAsStream(name)
                    ?: throw IllegalStateException("Word list resource $name is missing")
            return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readLines().filter { it.isNotEmpty() } }
        }
    }
}
