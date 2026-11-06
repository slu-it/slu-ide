package dev.sluide.games.wordguess

internal const val WORD_LENGTH = 5
internal const val MAX_GUESSES = 6

/** How a letter of a guess relates to the answer. */
internal enum class LetterState { CORRECT, PRESENT, ABSENT }

internal enum class RoundState { RUNNING, WON, LOST }

internal enum class SubmitResult { ACCEPTED, NOT_ENOUGH_LETTERS, NOT_IN_LIST, IGNORED }

/**
 * One round of Word Guess: up to [MAX_GUESSES] guesses of [WORD_LENGTH] letters at the [answer]. [isAllowed] decides
 * which words may be guessed, called with the upper-cased word (the answer always may); typed letters must be in
 * [alphabet].
 * Not thread-safe: use it on the FX thread only.
 */
internal class WordGuessGame(
    val answer: String,
    private val isAllowed: (String) -> Boolean,
    private val alphabet: String,
) {
    init {
        require(answer.length == WORD_LENGTH && answer.all { it in alphabet }) {
            "the answer must be $WORD_LENGTH letters of the alphabet: $answer"
        }
    }

    data class Guess(
        val word: String,
        val states: List<LetterState>,
    )

    private val typed = StringBuilder()
    private val made = mutableListOf<Guess>()
    private val best = mutableMapOf<Char, LetterState>()

    /** The letters typed in the current row. */
    val input: String get() = typed.toString()

    val guesses: List<Guess> get() = made.toList()

    var state: RoundState = RoundState.RUNNING
        private set

    /** The best known state per letter over all guesses: CORRECT over PRESENT over ABSENT. */
    val letterStates: Map<Char, LetterState> get() = best.toMap()

    /** Appends [letter] (upper-cased) if it fits; true when the input changed. */
    fun type(letter: Char): Boolean {
        val upper = letter.uppercaseChar()
        if (state != RoundState.RUNNING || typed.length >= WORD_LENGTH || upper !in alphabet) return false
        typed.append(upper)
        return true
    }

    fun backspace(): Boolean {
        if (state != RoundState.RUNNING || typed.isEmpty()) return false
        typed.setLength(typed.length - 1)
        return true
    }

    fun submit(): SubmitResult {
        if (state != RoundState.RUNNING) return SubmitResult.IGNORED
        if (typed.length < WORD_LENGTH) return SubmitResult.NOT_ENOUGH_LETTERS
        val word = input
        if (word != answer && !isAllowed(word)) return SubmitResult.NOT_IN_LIST
        val states = score(word, answer)
        made += Guess(word, states)
        word.forEachIndexed { i, c ->
            best[c] = best[c]?.let { better(it, states[i]) } ?: states[i]
        }
        typed.setLength(0)
        state =
            when {
                word == answer -> RoundState.WON
                made.size >= MAX_GUESSES -> RoundState.LOST
                else -> RoundState.RUNNING
            }
        return SubmitResult.ACCEPTED
    }
}

private fun better(
    a: LetterState,
    b: LetterState,
): LetterState =
    when {
        a == LetterState.CORRECT || b == LetterState.CORRECT -> LetterState.CORRECT
        a == LetterState.PRESENT || b == LetterState.PRESENT -> LetterState.PRESENT
        else -> LetterState.ABSENT
    }
