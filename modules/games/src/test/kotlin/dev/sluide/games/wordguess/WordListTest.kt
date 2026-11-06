package dev.sluide.games.wordguess

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WordListTest {
    private fun resource(name: String): List<String> =
        WordList::class.java
            .getResourceAsStream(name)!!
            .use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .also { assertTrue(it.endsWith("\n") && !it.contains('\r'), "$name: LF with a final newline") }
            .lines()
            .dropLast(1)

    @ParameterizedTest
    @EnumSource(Language::class)
    fun `every word has five letters of the alphabet, no duplicates and is sorted`(language: Language) {
        for (name in listOf("${language.code}-allowed.txt", "${language.code}-answers.txt")) {
            val words = resource(name)
            assertTrue(words.all { it.length == 5 && it.all { c -> c in language.alphabet } }, "$name: letters")
            assertEquals(words.sorted().distinct(), words, "$name: sorted without duplicates")
        }
    }

    @ParameterizedTest
    @EnumSource(Language::class)
    fun `answers are a subset of the allowed words`(language: Language) {
        val list = WordList.load(language)
        assertTrue(list.answers.all { list.isAllowed(it) })
        assertEquals(resource("${language.code}-answers.txt"), list.answers)
        assertTrue(resource("${language.code}-allowed.txt").containsAll(list.answers))
    }

    @Test
    fun `the lists have sensible sizes`() {
        assertTrue(WordList.load(Language.EN).answers.size in 1500..2600)
        assertTrue(WordList.load(Language.DE).answers.size in 1300..3000)
        assertTrue(resource("en-allowed.txt").size > 8000)
        assertTrue(resource("de-allowed.txt").size > 9000)
    }

    @Test
    fun `English knows common and obscure words`() {
        val en = WordList.load(Language.EN)
        assertTrue("CRANE" in en.answers)
        assertTrue(en.isAllowed("FICHE"))
        assertFalse("FICHE" in en.answers)
        assertFalse(en.isAllowed("XXXXX"))
    }

    @Test
    fun `German knows umlaut words and writes sharp s as SS`() {
        val de = WordList.load(Language.DE)
        assertTrue(de.isAllowed("MASSE"))
        assertTrue(de.isAllowed("SOSSE"))
        assertTrue("GRUSS" in de.answers)
        assertTrue(de.isAllowed("ÄRGER"))
        assertTrue(de.isAllowed("BÜHNE"))
        assertTrue("BÜHNE" in de.answers)
        assertFalse(de.isAllowed("STRASSE"))
    }

    @Test
    fun `load returns the same instance twice`() {
        assertSame(WordList.load(Language.EN), WordList.load(Language.EN))
        assertSame(WordList.load(Language.DE), WordList.load(Language.DE))
    }

    @Test
    fun `isAllowed rejects lower case and other lengths`() {
        val en = WordList.load(Language.EN)
        assertFalse(en.isAllowed("crane"))
        assertFalse(en.isAllowed("CRANES"))
        assertFalse(en.isAllowed(""))
    }
}
