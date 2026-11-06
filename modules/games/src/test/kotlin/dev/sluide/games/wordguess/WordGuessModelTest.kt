package dev.sluide.games.wordguess

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WordGuessModelTest {
    @TempDir
    lateinit var dir: Path

    private val english =
        WordList(listOf("CRANE", "SLATE", "MOUSE"), setOf("CRANE", "SLATE", "MOUSE", "ABOUT", "HOUSE", "ARISE"))
    private val german = WordList(listOf("BÜHNE", "FEIER"), setOf("BÜHNE", "FEIER", "HAUSE"))
    private val loads = mutableListOf<Language>()

    private fun preferences() =
        WordGuessPreferences(
            ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()),
            Duration.ZERO,
            Executor { it.run() },
        )

    private fun model(preferences: WordGuessPreferences = preferences()) =
        WordGuessModel(
            preferences,
            { language ->
                loads += language
                if (language == Language.EN) english else german
            },
            Random(7),
        )

    /** A word that is allowed and not the answer. */
    private fun WordGuessModel.wrongWord() = if (answer == "ABOUT") "HOUSE" else "ABOUT"

    private fun WordGuessModel.write(word: String) = word.forEach { type(it) }

    private fun WordGuessModel.lose() =
        repeat(MAX_GUESSES) {
            write(wrongWord())
            submit()
        }

    @Test
    fun `the word lists load on first use only`() {
        val model = model()
        assertEquals(emptyList(), loads)

        model.statusText

        assertEquals(listOf(Language.EN), loads)
    }

    @Test
    fun `the language of the last round is the starting language`() {
        val preferences = preferences()
        preferences.saveLanguage(Language.DE)

        val model = model(preferences)

        assertEquals(Language.DE, model.language)
        assertTrue(model.answer in german.answers)
    }

    @Test
    fun `the status counts the guesses`() {
        val model = model()
        assertEquals("Guess 1 of 6", model.statusText)

        model.write(model.wrongWord())
        model.submit()

        assertEquals("Guess 2 of 6", model.statusText)
    }

    @Test
    fun `a rejected row keeps its letters and says why until the input changes`() {
        val model = model()
        var changes = 0
        model.onChange = { changes++ }

        model.write("CRA")
        assertEquals(SubmitResult.NOT_ENOUGH_LETTERS, model.submit())
        assertEquals("Not enough letters", model.statusText)
        assertEquals("CRA", model.input)

        model.type('Z')
        model.type('Z')
        assertEquals("Guess 1 of 6", model.statusText)
        assertEquals(SubmitResult.NOT_IN_LIST, model.submit())
        assertEquals("Not in the word list", model.statusText)
        assertEquals("CRAZZ", model.input)

        model.backspace()
        assertEquals("Guess 1 of 6", model.statusText)
        assertEquals(8, changes)
    }

    @Test
    fun `winning shows the guess count and Enter starts the next word`() {
        val model = model()
        val first = model.answer
        model.write(model.wrongWord())
        model.submit()
        model.write(first)
        assertEquals(SubmitResult.ACCEPTED, model.submit())

        assertEquals(RoundState.WON, model.state)
        assertEquals("Solved in 2 of 6 — Enter for the next word", model.statusText)
        assertEquals(-1, model.activeRow)

        assertEquals(SubmitResult.IGNORED, model.submit())

        assertEquals(RoundState.RUNNING, model.state)
        assertNotEquals(first, model.answer)
        assertEquals("Guess 1 of 6", model.statusText)
    }

    @Test
    fun `losing shows the word`() {
        val model = model()
        val answer = model.answer

        model.lose()

        assertEquals(RoundState.LOST, model.state)
        assertEquals("The word was $answer — Enter for the next word", model.statusText)
    }

    @Test
    fun `skipping a running round shows its word until the first letter of the next`() {
        val model = model()
        val skipped = model.answer

        model.nextWord()

        assertEquals("Skipped: the word was $skipped", model.statusText)
        assertEquals(0, model.rows.flatten().count { it.letter != null })
        model.type('a')
        assertEquals("Guess 1 of 6", model.statusText)
    }

    @Test
    fun `skipping before anything started just starts a round`() {
        val model = model()

        model.nextWord()

        assertEquals("Guess 1 of 6", model.statusText)
    }

    @Test
    fun `skipping after the end just starts the next word`() {
        val model = model()
        model.lose()

        model.nextWord()

        assertEquals("Guess 1 of 6", model.statusText)
    }

    @Test
    fun `rows show guesses, the typed letters and empty tiles`() {
        val model = model()
        val word = model.wrongWord()
        model.write(word)
        model.submit()
        model.write("ab")

        val rows = model.rows

        assertEquals(MAX_GUESSES, rows.size)
        assertEquals(word, rows[0].map { it.letter }.joinToString(""))
        assertTrue(rows[0].all { it.state != null })
        assertEquals(listOf('A', 'B', null, null, null), rows[1].map { it.letter })
        assertTrue(rows[1].all { it.state == null })
        assertTrue(rows[2].all { it.letter == null })
        assertEquals(1, model.activeRow)
    }

    @Test
    fun `choosing another language saves it and starts a new word at once`() {
        val preferences = preferences()
        val model = model(preferences)
        model.write("CRA")
        var changes = 0
        model.onChange = { changes++ }

        model.chooseLanguage(Language.DE)

        assertEquals(Language.DE, model.language)
        assertEquals(Language.DE, preferences.lastLanguage)
        assertEquals(Language.DE, model(preferences()).language)
        assertTrue(model.answer in german.answers)
        assertEquals("", model.input)
        assertEquals("Guess 1 of 6", model.statusText)
        assertEquals(1, changes)

        model.type('ü')
        model.type('ß')
        assertEquals("Ü", model.input)

        model.chooseLanguage(Language.DE)
        assertEquals(2, changes)
    }

    @Test
    fun `every language draws from its own bag`() {
        val model = model()
        val seen = mutableListOf(model.answer)
        model.nextWord()
        seen += model.answer
        model.chooseLanguage(Language.DE)
        val german = model.answer
        model.chooseLanguage(Language.EN)
        seen += model.answer
        model.nextWord()
        model.chooseLanguage(Language.DE)

        // The English bag went on after the German detour: three different words before a repeat is possible.
        assertEquals(3, seen.take(3).distinct().size)
        assertNotEquals(german, model.answer)
        assertEquals(listOf(Language.EN, Language.DE), loads)
    }
}
