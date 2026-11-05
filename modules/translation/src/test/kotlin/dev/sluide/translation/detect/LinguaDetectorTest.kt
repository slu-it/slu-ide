package dev.sluide.translation.detect

import dev.sluide.translation.engine.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class LinguaDetectorTest {
    private val en = Language("en", "English")
    private val de = Language("de", "German")
    private val sv = Language("sv", "Swedish")
    private val fr = Language("fr", "French")
    private val all = listOf(en, de, sv, fr)
    private val german = "Ich habe gestern einen langen Spaziergang im Wald gemacht."

    private val detector = LinguaDetector()

    @Test
    fun `detects one sentence each of German, English, Swedish and French`() {
        assertEquals(de, detector.detect(german, all))
        assertEquals(en, detector.detect("Yesterday I took a long walk in the forest with my dog.", all))
        assertEquals(sv, detector.detect("Igår gick jag en lång promenad i skogen med min hund.", all))
        assertEquals(fr, detector.detect("Hier, j'ai fait une longue promenade dans la forêt avec mon chien.", all))
    }

    @Test
    fun `short text is answered only when it is clear`() {
        assertEquals(de, detector.detect("Guten Tag", all))
        assertNull(detector.detect("Hello world", all))
    }

    @Test
    fun `digits and punctuation only give null`() {
        assertNull(detector.detect("12345 67890 ... !?! 2024-01-01", all))
        assertNull(detector.detect("   ", all))
    }

    @Test
    fun `the answer is limited to the requested languages`() {
        // Among all of Lingua's languages this is Dutch; among German and English, German comes out clearly.
        val nl = Language("nl", "Dutch")
        val dutch = "Gisteren heb ik een lange wandeling door het bos gemaakt met mijn hond."
        assertEquals(de, detector.detect(dutch, listOf(de, en)))
        assertEquals(nl, detector.detect(dutch, listOf(de, en, nl)))
    }

    @Test
    fun `unknown codes are skipped and fewer than two known languages give null`() {
        val unknown = Language("xx", "Nothing")
        assertEquals(de, detector.detect(german, listOf(unknown, de, en)))
        assertNull(detector.detect(german, listOf(unknown, de)))
        assertNull(detector.detect(german, listOf(de)))
    }

    @Test
    fun `detectors are cached per language set`() {
        detector.detect(german, listOf(de, en))
        detector.detect(german, listOf(en, de))
        assertEquals(1, detector.cachedDetectors)
        detector.detect(german, listOf(de, en, sv))
        assertEquals(2, detector.cachedDetectors)
    }

    @Test
    fun `only the first 10000 characters count`() {
        assertNull(detector.detect("1 ".repeat(5_000) + german, all))
        assertEquals(de, detector.detect("1 ".repeat(4_990) + german, all))
    }

    @ParameterizedTest
    @CsvSource("no,BOKMAL", "jap,JAPANESE", "de,GERMAN", "deu,GERMAN", "sv,SWEDISH", "swe,SWEDISH", "DE,GERMAN")
    fun `Opus codes map to Lingua languages`(
        code: String,
        expected: String,
    ) {
        assertEquals(expected, LinguaDetector.linguaLanguage(code)?.name)
    }

    @ParameterizedTest
    @ValueSource(strings = ["mul", "gem", "gmw", "xx", ""])
    fun `group and unknown codes are skipped`(code: String) {
        assertNull(LinguaDetector.linguaLanguage(code))
    }
}
