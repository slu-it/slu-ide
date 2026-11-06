package dev.sluide.games.wordguess

/**
 * Scores [guess] against [answer] (same length, upper case): exact positions are CORRECT; every other letter, left to
 * right, is PRESENT while the answer still has an unmatched copy of it, else ABSENT.
 */
internal fun score(
    guess: String,
    answer: String,
): List<LetterState> {
    require(guess.length == answer.length) { "guess and answer differ in length" }
    val states = MutableList(guess.length) { LetterState.ABSENT }
    val remaining = HashMap<Char, Int>()
    for (i in guess.indices) {
        if (guess[i] == answer[i]) {
            states[i] = LetterState.CORRECT
        } else {
            remaining.merge(answer[i], 1, Int::plus)
        }
    }
    for (i in guess.indices) {
        if (states[i] == LetterState.CORRECT) continue
        val left = remaining[guess[i]] ?: 0
        if (left > 0) {
            states[i] = LetterState.PRESENT
            remaining[guess[i]] = left - 1
        }
    }
    return states
}
