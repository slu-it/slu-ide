package dev.sluide.games.wordguess

import kotlin.random.Random

/**
 * Draws answers without repeats: every word once in random order, then a reshuffle. After a refill the first word
 * differs from the last one drawn when there is more than one answer.
 */
internal class WordBag(
    answers: List<String>,
    private val random: Random = Random.Default,
) {
    private val answers = answers.distinct()

    private val queue = ArrayDeque<String>()
    private var last: String? = null

    init {
        require(this.answers.isNotEmpty()) { "no answers" }
    }

    fun next(): String {
        if (queue.isEmpty()) refill()
        return queue.removeFirst().also { last = it }
    }

    private fun refill() {
        val shuffled = answers.shuffled(random).toMutableList()
        if (shuffled.size > 1 && shuffled.first() == last) {
            val other = 1 + random.nextInt(shuffled.size - 1)
            shuffled[0] = shuffled[other].also { shuffled[other] = shuffled[0] }
        }
        queue.addAll(shuffled)
    }
}
