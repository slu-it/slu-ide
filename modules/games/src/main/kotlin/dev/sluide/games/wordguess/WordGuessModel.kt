package dev.sluide.games.wordguess

import kotlin.random.Random

/** One tile of the grid: a typed or guessed [letter] (null when empty) and, for a submitted guess, its [state]. */
internal data class Tile(
    val letter: Char?,
    val state: LetterState?,
)

/**
 * The Word Guess round of one window and what the view shows of it. Outlives the tab (it is owned by
 * [dev.sluide.games.WordGuess]); the view that shows it owns [onChange] until it is disposed. FX thread.
 *
 * The word lists of a language are read on first use, synchronously: they are small (a few ms), so a one-time read
 * on the FX thread is not noticeable, and the round needs them at once. Each language has its own [WordBag], so
 * switching back and forth does not repeat words.
 */
internal class WordGuessModel(
    private val preferences: WordGuessPreferences,
    private val lists: (Language) -> WordList = { WordList.load(it) },
    private val random: Random = Random.Default,
) {
    /** Called after every change of what the view shows. */
    var onChange: () -> Unit = {}

    private var chosen: Language? = null
    private var current: WordGuessGame? = null
    private val loaded = mutableMapOf<Language, WordList>()
    private val bags = mutableMapOf<Language, WordBag>()

    /** A rejection or skip message that replaces the status until the input changes (or the next submit). */
    private var notice: String? = null

    val language: Language get() = chosen ?: preferences.lastLanguage.also { chosen = it }

    private val game: WordGuessGame get() = current ?: startRound().also { current = it }

    /** The answer of the round; shown only after it ended or was skipped. */
    val answer: String get() = game.answer

    val input: String get() = game.input

    val state: RoundState get() = game.state

    val letterStates: Map<Char, LetterState> get() = game.letterStates

    /** The row being typed in, or -1 when the round ended. */
    val activeRow: Int get() = if (game.state == RoundState.RUNNING) game.guesses.size else -1

    /** All [MAX_GUESSES] rows of [WORD_LENGTH] tiles: guesses, the typed letters, then empty tiles. */
    val rows: List<List<Tile>>
        get() {
            val guesses = game.guesses
            return List(MAX_GUESSES) { row ->
                when {
                    row < guesses.size -> guesses[row].let { g -> g.word.mapIndexed { i, c -> Tile(c, g.states[i]) } }
                    row == activeRow -> List(WORD_LENGTH) { Tile(game.input.getOrNull(it), null) }
                    else -> List(WORD_LENGTH) { Tile(null, null) }
                }
            }
        }

    val statusText: String
        get() {
            notice?.let { return it }
            val made = game.guesses.size
            return when (game.state) {
                RoundState.RUNNING -> "Guess ${made + 1} of $MAX_GUESSES"
                RoundState.WON -> "Solved in $made of $MAX_GUESSES — Enter for the next word"
                RoundState.LOST -> "The word was ${game.answer} — Enter for the next word"
            }
        }

    /** Switches to [language] with a new word at once and saves it; the same language changes nothing. */
    fun chooseLanguage(language: Language) {
        if (language == this.language) return
        chosen = language
        preferences.saveLanguage(language)
        notice = null
        current = startRound()
        onChange()
    }

    /** A new word; a running round is skipped and shows its word until the first letter of the new one is typed. */
    fun nextWord() {
        val running = current?.takeIf { it.state == RoundState.RUNNING }
        notice = running?.let { "Skipped: the word was ${it.answer}" }
        current = startRound()
        onChange()
    }

    fun type(letter: Char) {
        if (game.type(letter)) inputChanged()
    }

    fun backspace() {
        if (game.backspace()) inputChanged()
    }

    /** Submits the row, or starts the next word when the round ended; the result tells the view to shake a row. */
    fun submit(): SubmitResult {
        if (game.state != RoundState.RUNNING) {
            nextWord()
            return SubmitResult.IGNORED
        }
        val result = game.submit()
        notice =
            when (result) {
                SubmitResult.NOT_ENOUGH_LETTERS -> "Not enough letters"
                SubmitResult.NOT_IN_LIST -> "Not in the word list"
                else -> null
            }
        onChange()
        return result
    }

    private fun inputChanged() {
        notice = null
        onChange()
    }

    private fun startRound(): WordGuessGame {
        val language = language
        val list = loaded.getOrPut(language) { lists(language) }
        val bag = bags.getOrPut(language) { WordBag(list.answers, random) }
        return WordGuessGame(bag.next(), list::isAllowed, language.alphabet)
    }
}
